//! Deterministic MODOSE domain core.
//!
//! Domain types and behavior are introduced by M-029 and later issues.

#![forbid(unsafe_code)]

pub mod local_restoration;
pub mod restoration_session;
pub mod table_projection;
pub mod projected_restoration;
pub mod guidance;
pub mod pair_confidence;
pub mod object_assignment;
pub mod matched_restoration;
pub mod native_runtime;
pub mod frame_ingress;
