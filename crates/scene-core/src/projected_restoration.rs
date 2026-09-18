//! A fixed table plane and restoration session with fail-closed projection.
//! The caller must supply rays in the same ARCore world frame for this lifetime.

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
    Located { ray: WorldRay, orientation_aligned: bool },
}

#[derive(Debug, Clone, Copy, PartialEq, Eq)]
pub enum ProjectedRestoreError {
    Projection(ProjectionError),
    Restore(RestoreError),
}

#[derive(Debug)]
pub struct ProjectedRestorationSession {
    plane: TablePlane,
    session: RestorationSession,
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
        Ok(Self { plane, session })
    }

    pub fn observe(
        &mut self,
        object_id: u32,
        now_ms: u64,
        observation: WorldObservation,
    ) -> Result<LocalState, ProjectedRestoreError> {
        let local = match observation {
            WorldObservation::Unobserved => Observation::Unobserved,
            WorldObservation::Missing => Observation::Missing,
            WorldObservation::Ambiguous => Observation::Ambiguous,
            WorldObservation::TrackingLost => Observation::TrackingLost,
            WorldObservation::Located { ray, orientation_aligned } => {
                match self.plane.project(ray) {
                    Ok(position) => Observation::Located { position, orientation_aligned },
                    Err(error) => {
                        // Do not return early leaving an aligned object or an
                        // in-flight verification valid after projection failure.
                        self.session.observe(object_id, now_ms, Observation::TrackingLost)
                            .map_err(ProjectedRestoreError::Restore)?;
                        return Err(ProjectedRestoreError::Projection(error));
                    }
                }
            }
        };
        self.session.observe(object_id, now_ms, local)
            .map_err(ProjectedRestoreError::Restore)
    }

    pub fn state(&self, now_ms: u64) -> RestoreState {
        self.session.state(now_ms)
    }

    pub fn begin_verification(
        &mut self,
        now_ms: u64,
    ) -> Result<VerificationTicket, RestoreError> {
        self.session.begin_verification(now_ms)
    }

    pub fn complete_verification(
        &mut self,
        ticket: VerificationTicket,
        now_ms: u64,
        result: VerificationResult,
    ) -> Result<RestoreState, RestoreError> {
        self.session.complete_verification(ticket, now_ms, result)
    }

    pub fn verification_attempts(&self) -> u8 {
        self.session.verification_attempts()
    }
}
