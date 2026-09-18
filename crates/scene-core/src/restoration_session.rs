//! Session-scoped restoration gate. No network, ARCore or Android ownership.

use crate::local_restoration::{
    LocalError, LocalRestoration, LocalState, Observation, TablePosition,
};

#[derive(Debug, Clone, Copy, PartialEq, Eq)]
pub enum RestoreState {
    Guiding,
    AwaitingVerification,
    Verifying,
    Verified,
    ManualConfirmation,
}

#[derive(Debug, Clone, Copy, PartialEq, Eq)]
pub enum Verdict {
    Verified,
    NeedsCorrection,
    Uncertain,
}

#[derive(Debug)]
pub enum VerificationResult {
    Analyzed {
        overall: Verdict,
        objects: Vec<(u32, Verdict)>,
    },
    Unavailable,
}

#[derive(Debug, Clone, Copy, PartialEq, Eq)]
pub struct VerificationTicket {
    session_id: u128,
    attempt: u8,
}

#[derive(Debug, Clone, Copy, PartialEq, Eq)]
pub enum RestoreError {
    InvalidSessionId,
    InvalidObjectSet,
    UnknownObject,
    Local(LocalError),
    ClockRegressed,
    NotLocallyAligned,
    VerificationInProgress,
    AlreadyVerified,
    AttemptsExhausted,
    StaleVerification,
    InvalidVerificationObjects,
}

#[derive(Debug)]
pub struct RestorationSession {
    session_id: u128,
    objects: Vec<(u32, LocalRestoration)>,
    last_event_ms: Option<u64>,
    attempts: u8,
    pending: Option<VerificationTicket>,
    verified: bool,
}

impl RestorationSession {
    /// session_id must be unique per ARCore session and never reused on reset.
    pub fn new(
        session_id: u128,
        targets: &[(u32, TablePosition)],
    ) -> Result<Self, RestoreError> {
        if session_id == 0 {
            return Err(RestoreError::InvalidSessionId);
        }
        if !(1..=5).contains(&targets.len())
            || targets.iter().enumerate().any(|(i, (id, _))| {
                *id == 0 || targets[..i].iter().any(|(other, _)| other == id)
            })
        {
            return Err(RestoreError::InvalidObjectSet);
        }
        let objects = targets.iter().map(|(id, target)| {
            LocalRestoration::new(*target)
                .map(|local| (*id, local))
                .map_err(RestoreError::Local)
        }).collect::<Result<Vec<_>, _>>()?;
        Ok(Self {
            session_id,
            objects,
            last_event_ms: None,
            attempts: 0,
            pending: None,
            verified: false,
        })
    }

    pub fn observe(
        &mut self,
        object_id: u32,
        now_ms: u64,
        observation: Observation,
    ) -> Result<LocalState, RestoreError> {
        self.advance_clock(now_ms)?;
        let Some(index) = self.objects.iter().position(|(id, _)| *id == object_id) else {
            self.invalidate_all();
            return Err(RestoreError::UnknownObject);
        };
        let result = self.objects[index].1.observe(now_ms, observation);
        if !self.objects[index].1.is_aligned_at(now_ms) {
            self.pending = None;
            self.verified = false;
        }
        result.map_err(RestoreError::Local)
    }

    /// A freshness-aware state. A historical Verified flag alone is insufficient.
    pub fn state(&self, now_ms: u64) -> RestoreState {
        if self.attempts >= 3 && self.pending.is_none() && !self.verified {
            return RestoreState::ManualConfirmation;
        }
        if self.last_event_ms.is_some_and(|last| now_ms < last)
            || !self.all_aligned(now_ms)
        {
            return RestoreState::Guiding;
        }
        if self.verified {
            RestoreState::Verified
        } else if self.pending.is_some() {
            RestoreState::Verifying
        } else {
            RestoreState::AwaitingVerification
        }
    }

    pub fn begin_verification(
        &mut self,
        now_ms: u64,
    ) -> Result<VerificationTicket, RestoreError> {
        self.advance_clock(now_ms)?;
        if !self.all_aligned(now_ms) {
            self.invalidate_all();
            return Err(RestoreError::NotLocallyAligned);
        }
        if self.verified {
            return Err(RestoreError::AlreadyVerified);
        }
        if self.pending.is_some() {
            return Err(RestoreError::VerificationInProgress);
        }
        if self.attempts >= 3 {
            return Err(RestoreError::AttemptsExhausted);
        }
        self.attempts += 1;
        let ticket = VerificationTicket {
            session_id: self.session_id,
            attempt: self.attempts,
        };
        self.pending = Some(ticket);
        Ok(ticket)
    }

    /// Timeouts and transport failures must be delivered as Unavailable.
    /// All retries require a new ticket and consume the three-attempt budget.
    pub fn complete_verification(
        &mut self,
        ticket: VerificationTicket,
        now_ms: u64,
        result: VerificationResult,
    ) -> Result<RestoreState, RestoreError> {
        self.advance_clock(now_ms)?;
        if self.pending != Some(ticket) {
            return Err(RestoreError::StaleVerification);
        }
        self.pending = None;
        if !self.all_aligned(now_ms) {
            self.invalidate_all();
            return Err(RestoreError::NotLocallyAligned);
        }
        match result {
            VerificationResult::Unavailable => {}
            VerificationResult::Analyzed { overall, objects } => {
                let exact_object_set = objects.len() == self.objects.len()
                    && self.objects.iter().all(|(id, _)| {
                        objects.iter().filter(|(returned, _)| returned == id).count() == 1
                    });
                if !exact_object_set {
                    self.invalidate_all();
                    return Err(RestoreError::InvalidVerificationObjects);
                }
                self.verified = overall == Verdict::Verified
                    && objects.iter().all(|(_, verdict)| *verdict == Verdict::Verified);
                if overall == Verdict::NeedsCorrection
                    || objects.iter().any(|(_, verdict)| *verdict == Verdict::NeedsCorrection)
                {
                    self.invalidate_all();
                }
            }
        }
        Ok(self.state(now_ms))
    }

    pub fn verification_attempts(&self) -> u8 {
        self.attempts
    }

    fn all_aligned(&self, now_ms: u64) -> bool {
        self.objects.iter().all(|(_, local)| local.is_aligned_at(now_ms))
    }

    fn advance_clock(&mut self, now_ms: u64) -> Result<(), RestoreError> {
        if self.last_event_ms.is_some_and(|previous| now_ms < previous) {
            self.invalidate_all();
            return Err(RestoreError::ClockRegressed);
        }
        self.last_event_ms = Some(now_ms);
        Ok(())
    }

    fn invalidate_all(&mut self) {
        self.pending = None;
        self.verified = false;
        for (_, local) in &mut self.objects {
            local.invalidate();
        }
    }
}
