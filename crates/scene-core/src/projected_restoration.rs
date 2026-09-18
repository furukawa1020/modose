//! A fixed table plane and restoration session with fail-closed projection.
//! The caller must supply rays in the same ARCore world frame for this lifetime.

use crate::guidance::{
    plan_guidance, Guidance, GuidanceError, GuidanceSample, MIN_GUIDANCE_CONFIDENCE,
};
use crate::local_restoration::{LocalState, Observation, TablePosition};
use crate::restoration_session::{
    RestorationSession, RestoreError, RestoreState, VerificationResult, VerificationTicket,
};
use crate::table_projection::{ProjectionError, TablePlane, WorldRay};

#[derive(Debug, Clone, Copy)]
pub enum WorldObservation {
    Unobserved,
    Missing,
    Ambiguous,
    TrackingLost,
    // Only use after valid tracking and unambiguous object association.
    Located {
        ray: WorldRay,
        orientation_aligned: bool,
        confidence: f64,
        occludes_other: bool,
    },
}

#[derive(Debug, Clone, Copy, PartialEq, Eq)]
pub enum ProjectedRestoreError {
    Projection(ProjectionError),
    Restore(RestoreError),
    InvalidConfidence,
}

#[derive(Debug)]
pub struct ProjectedRestorationSession {
    plane: TablePlane,
    session: RestorationSession,
    guidance_samples: Vec<GuidanceSample>,
}

impl ProjectedRestorationSession {
    /// The plane and targets cannot be replaced within this session.
    /// Re-localization that changes the world frame requires a new session ID.
    pub fn new(
        session_id: u128,
        plane: TablePlane,
        targets: &[(u32, TablePosition)],
    ) -> Result<Self, ProjectedRestoreError> {
        let session = RestorationSession::new(session_id, targets)
            .map_err(ProjectedRestoreError::Restore)?;
        if targets.iter().any(|(_, target)| !plane.contains(*target)) {
            return Err(ProjectedRestoreError::Projection(ProjectionError::OutsidePlane));
        }
        let guidance_samples = targets.iter().map(|&(object_id, target)| GuidanceSample {
            object_id, target, current: None, observed_at_ms: None,
            local_state: LocalState::Unobserved, confidence: 0.0,
            occludes_other: false, orientation_aligned: false,
        }).collect();
        Ok(Self { plane, session, guidance_samples })
    }

    pub fn observe(
        &mut self,
        object_id: u32,
        now_ms: u64,
        observation: WorldObservation,
    ) -> Result<LocalState, ProjectedRestoreError> {
        let (local, confidence, occludes_other) = match observation {
            WorldObservation::Unobserved => (Observation::Unobserved, 0.0, false),
            WorldObservation::Missing => (Observation::Missing, 0.0, false),
            WorldObservation::Ambiguous => (Observation::Ambiguous, 0.0, false),
            WorldObservation::TrackingLost => (Observation::TrackingLost, 0.0, false),
            WorldObservation::Located {
                ray, orientation_aligned, confidence, occludes_other,
            } => {
                if !confidence.is_finite()
                    || !(MIN_GUIDANCE_CONFIDENCE..=1.0).contains(&confidence)
                {
                    self.discard_guidance();
                    self.session.observe(object_id, now_ms, Observation::TrackingLost)
                        .map_err(ProjectedRestoreError::Restore)?;
                    return Err(ProjectedRestoreError::InvalidConfidence);
                }
                match self.plane.project(ray) {
                    Ok(position) => (
                        Observation::Located { position, orientation_aligned },
                        confidence,
                        occludes_other,
                    ),
                    Err(error) => {
                        self.discard_guidance();
                        self.session.observe(object_id, now_ms, Observation::TrackingLost)
                            .map_err(ProjectedRestoreError::Restore)?;
                        return Err(ProjectedRestoreError::Projection(error));
                    }
                }
            }
        };
        let state = match self.session.observe(object_id, now_ms, local) {
            Ok(state) => state,
            Err(error) => {
                self.discard_guidance();
                return Err(ProjectedRestoreError::Restore(error));
            }
        };
        if let Some(sample) = self.guidance_samples.iter_mut()
            .find(|sample| sample.object_id == object_id)
        {
            let (current, orientation_aligned) = match local {
                Observation::Located { position, orientation_aligned } => {
                    (Some(position), orientation_aligned)
                }
                _ => (None, false),
            };
            sample.current = current;
            sample.orientation_aligned = orientation_aligned;
            sample.local_state = state;
            sample.observed_at_ms = Some(now_ms);
            sample.confidence = confidence;
            sample.occludes_other = occludes_other;
        }
        Ok(state)
    }

    pub fn next_guidance(&self, now_ms: u64) -> Result<Option<Guidance>, GuidanceError> {
        if self.session.state(now_ms) != RestoreState::Guiding {
            return Ok(None);
        }
        plan_guidance(now_ms, &self.guidance_samples)
    }

    pub fn state(&self, now_ms: u64) -> RestoreState {
        self.session.state(now_ms)
    }

    pub fn begin_verification(
        &mut self,
        now_ms: u64,
    ) -> Result<VerificationTicket, RestoreError> {
        let result = self.session.begin_verification(now_ms);
        if result.is_err() {
            self.discard_guidance();
        }
        result
    }

    pub fn complete_verification(
        &mut self,
        ticket: VerificationTicket,
        now_ms: u64,
        result: VerificationResult,
    ) -> Result<RestoreState, RestoreError> {
        let result = self.session.complete_verification(ticket, now_ms, result);
        // Non-success responses require new observations before reusing guidance.
        if !matches!(&result, Ok(RestoreState::Verified)) {
            self.discard_guidance();
        }
        result
    }

    pub fn verification_attempts(&self) -> u8 {
        self.session.verification_attempts()
    }

    fn discard_guidance(&mut self) {
        for sample in &mut self.guidance_samples {
            sample.current = None;
            sample.observed_at_ms = None;
            sample.local_state = LocalState::Unobserved;
            sample.confidence = 0.0;
            sample.occludes_other = false;
            sample.orientation_aligned = false;
        }
    }
}
