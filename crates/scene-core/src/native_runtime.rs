//! JNI-facing session ownership without pointers or Android references.
//! The actual JNI adapter must serialize access to one runtime instance.

use std::collections::BTreeMap;
use std::sync::atomic::{AtomicU64, Ordering};

use crate::guidance::{Guidance, GuidanceError};
use crate::local_restoration::TablePosition;
use crate::matched_restoration::{
    CurrentDetection, FrameError, MatchedRestorationSession, RestorationEvidence,
};
use crate::projected_restoration::ProjectedRestoreError;
use crate::restoration_session::{
    RestoreError, RestoreState, VerificationResult, VerificationTicket,
};
use crate::table_projection::TablePlane;

pub const MAX_NATIVE_SESSIONS: usize = 4;
static NEXT_HANDLE: AtomicU64 = AtomicU64::new(1);

#[derive(Debug, Clone, Copy, PartialEq, Eq, PartialOrd, Ord)]
pub struct SessionHandle(u64);

impl SessionHandle {
    pub fn from_raw(raw: i64) -> Result<Self, NativeError> {
        if raw <= 0 {
            return Err(NativeError::UnknownHandle);
        }
        Ok(Self(raw as u64))
    }

    pub fn as_raw(self) -> i64 {
        self.0 as i64
    }
}

#[derive(Debug, Clone, Copy, PartialEq, Eq)]
pub enum NativeError {
    CapacityExceeded,
    HandleSpaceExhausted,
    UnknownHandle,
    Create(ProjectedRestoreError),
    Frame(FrameError),
    Guidance(GuidanceError),
    Restore(RestoreError),
}

#[derive(Debug, Default)]
pub struct NativeRuntime {
    sessions: BTreeMap<SessionHandle, MatchedRestorationSession>,
}

impl NativeRuntime {
    pub fn create(
        &mut self,
        plane: TablePlane,
        targets: &[(u32, TablePosition)],
    ) -> Result<SessionHandle, NativeError> {
        if self.sessions.len() >= MAX_NATIVE_SESSIONS {
            return Err(NativeError::CapacityExceeded);
        }
        // Never reuse a handle, including across runtime instances. A rejected
        // creation may consume an ID; exhaustion is an error, never wraparound.
        let id = NEXT_HANDLE.fetch_update(Ordering::Relaxed, Ordering::Relaxed, |next| {
            if next <= i64::MAX as u64 { Some(next + 1) } else { None }
        }).map_err(|_| NativeError::HandleSpaceExhausted)?;
        let session = MatchedRestorationSession::new(id as u128, plane, targets)
            .map_err(NativeError::Create)?;
        let handle = SessionHandle(id);
        self.sessions.insert(handle, session);
        Ok(handle)
    }

    pub fn update_frame(
        &mut self,
        handle: SessionHandle,
        now_ms: u64,
        tracking_valid: bool,
        detections: &[CurrentDetection],
        evidence: &[RestorationEvidence],
    ) -> Result<RestoreState, NativeError> {
        self.get_mut(handle)?.update_frame(now_ms, tracking_valid, detections, evidence)
            .map_err(NativeError::Frame)
    }

    pub fn state(&self, handle: SessionHandle, now_ms: u64) -> Result<RestoreState, NativeError> {
        Ok(self.get(handle)?.state(now_ms))
    }

    pub fn guidance(
        &self,
        handle: SessionHandle,
        now_ms: u64,
    ) -> Result<Option<Guidance>, NativeError> {
        self.get(handle)?.next_guidance(now_ms).map_err(NativeError::Guidance)
    }

    pub fn begin_verification(
        &mut self,
        handle: SessionHandle,
        now_ms: u64,
    ) -> Result<VerificationTicket, NativeError> {
        self.get_mut(handle)?.begin_verification(now_ms).map_err(NativeError::Restore)
    }

    pub fn complete_verification(
        &mut self,
        handle: SessionHandle,
        ticket: VerificationTicket,
        now_ms: u64,
        result: VerificationResult,
    ) -> Result<RestoreState, NativeError> {
        self.get_mut(handle)?.complete_verification(ticket, now_ms, result)
            .map_err(NativeError::Restore)
    }

    pub fn close(&mut self, handle: SessionHandle) -> Result<(), NativeError> {
        self.sessions.remove(&handle).map(|_| ()).ok_or(NativeError::UnknownHandle)
    }

    pub fn close_all(&mut self) {
        self.sessions.clear();
    }

    pub fn active_sessions(&self) -> usize {
        self.sessions.len()
    }

    fn get(&self, handle: SessionHandle) -> Result<&MatchedRestorationSession, NativeError> {
        self.sessions.get(&handle).ok_or(NativeError::UnknownHandle)
    }

    fn get_mut(
        &mut self,
        handle: SessionHandle,
    ) -> Result<&mut MatchedRestorationSession, NativeError> {
        self.sessions.get_mut(&handle).ok_or(NativeError::UnknownHandle)
    }
}
