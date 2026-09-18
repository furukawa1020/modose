//! Deterministic selection of one safe guidance action, with no rendering code.

use crate::local_restoration::{
    LocalState, TablePosition, ALIGN_DISTANCE_M, MAX_OBSERVATION_GAP_MS,
};

pub const MIN_GUIDANCE_CONFIDENCE: f64 = 0.78;

#[derive(Debug, Clone, Copy)]
pub struct GuidanceSample {
    pub object_id: u32,
    pub target: TablePosition,
    pub current: Option<TablePosition>,
    pub observed_at_ms: Option<u64>,
    pub local_state: LocalState,
    pub confidence: f64,
    pub occludes_other: bool,
    pub orientation_aligned: bool,
}

#[derive(Debug, Clone, Copy, PartialEq, Eq)]
pub enum RecoveryReason {
    Unobserved,
    TrackingLost,
    Missing,
    Ambiguous,
}

#[derive(Debug, Clone, Copy, PartialEq)]
pub enum GuidanceAction {
    Move { from: TablePosition, to: TablePosition, distance_m: f64 },
    Ring { target: TablePosition },
    CheckOrientation { target: TablePosition },
    Recover { reason: RecoveryReason },
}

#[derive(Debug, Clone, Copy, PartialEq)]
pub struct Guidance {
    pub object_id: u32,
    pub action: GuidanceAction,
}

#[derive(Debug, Clone, Copy, PartialEq, Eq)]
pub enum GuidanceError {
    InvalidObjectSet,
    InvalidPosition,
    InvalidConfidence,
    InvalidTimestamp,
    MissingPosition,
}

struct Ranked {
    group: u8,
    occludes: bool,
    confidence: f64,
    distance: f64,
    guidance: Guidance,
}

pub fn plan_guidance(
    now_ms: u64,
    samples: &[GuidanceSample],
) -> Result<Option<Guidance>, GuidanceError> {
    if !(1..=5).contains(&samples.len())
        || samples.iter().enumerate().any(|(i, sample)| {
            sample.object_id == 0
                || samples[..i].iter().any(|other| other.object_id == sample.object_id)
        })
    {
        return Err(GuidanceError::InvalidObjectSet);
    }
    let mut ranked = Vec::with_capacity(samples.len());
    for sample in samples {
        if !sample.target.is_finite()
            || sample.current.is_some_and(|position| !position.is_finite())
        {
            return Err(GuidanceError::InvalidPosition);
        }
        if !sample.confidence.is_finite() || !(0.0..=1.0).contains(&sample.confidence) {
            return Err(GuidanceError::InvalidConfidence);
        }
        if sample.observed_at_ms.is_some_and(|observed| observed > now_ms) {
            return Err(GuidanceError::InvalidTimestamp);
        }
        let state = match sample.observed_at_ms {
            None => LocalState::Unobserved,
            Some(observed) if now_ms - observed > MAX_OBSERVATION_GAP_MS => {
                LocalState::TrackingLost
            }
            _ => sample.local_state,
        };
        let recovery = match state {
            LocalState::Unobserved => Some((1, RecoveryReason::Unobserved)),
            LocalState::TrackingLost => Some((1, RecoveryReason::TrackingLost)),
            LocalState::Missing => Some((2, RecoveryReason::Missing)),
            LocalState::Ambiguous => Some((3, RecoveryReason::Ambiguous)),
            _ => None,
        };
        if let Some((group, reason)) = recovery {
            ranked.push(Ranked {
                group, occludes: false, confidence: 0.0, distance: 0.0,
                guidance: Guidance {
                    object_id: sample.object_id,
                    action: GuidanceAction::Recover { reason },
                },
            });
            continue;
        }
        if sample.confidence < MIN_GUIDANCE_CONFIDENCE {
            return Err(GuidanceError::InvalidConfidence);
        }
        let current = sample.current.ok_or(GuidanceError::MissingPosition)?;
        let distance = (current.x - sample.target.x).hypot(current.z - sample.target.z);
        if !distance.is_finite() {
            return Err(GuidanceError::InvalidPosition);
        }
        if state == LocalState::Aligned {
            continue;
        }
        let action = if distance > ALIGN_DISTANCE_M {
            GuidanceAction::Move { from: current, to: sample.target, distance_m: distance }
        } else if !sample.orientation_aligned {
            GuidanceAction::CheckOrientation { target: sample.target }
        } else {
            GuidanceAction::Ring { target: sample.target }
        };
        ranked.push(Ranked {
            group: 0,
            occludes: sample.occludes_other,
            confidence: sample.confidence,
            distance,
            guidance: Guidance { object_id: sample.object_id, action },
        });
    }
    ranked.sort_by(|left, right| {
        left.group.cmp(&right.group)
            .then_with(|| right.occludes.cmp(&left.occludes))
            .then_with(|| right.confidence.total_cmp(&left.confidence))
            .then_with(|| left.distance.total_cmp(&right.distance))
            .then_with(|| left.guidance.object_id.cmp(&right.guidance.object_id))
    });
    Ok(ranked.first().map(|candidate| candidate.guidance))
}
