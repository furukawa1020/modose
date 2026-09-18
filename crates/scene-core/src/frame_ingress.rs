//! Versioned, bounded frame payload for a future JNI byte-array adapter.
//! All multibyte numbers use big endian. Handles and observation times are
//! separate native arguments, so malformed payloads can invalidate the owner.

use crate::matched_restoration::{CurrentDetection, RestorationEvidence};
use crate::native_runtime::{NativeError, NativeRuntime, SessionHandle};
use crate::object_assignment::PairEvidence;
use crate::pair_confidence::PairScore;
use crate::restoration_session::RestoreState;
use crate::table_projection::{WorldRay, WorldVector};

pub const MAX_FRAME_PACKET_BYTES: usize = 4096;

#[derive(Debug, Clone, Copy, PartialEq, Eq)]
pub enum FrameDecodeError {
    TooLarge,
    Truncated,
    InvalidMagic,
    UnsupportedVersion,
    InvalidCount,
    InvalidBoolean,
    ReservedBits,
    InvalidLength,
    InvalidId,
    InvalidNumber,
    InvalidScore,
}

#[derive(Debug, Clone, Copy, PartialEq, Eq)]
pub enum FrameIngressError {
    Decode(FrameDecodeError),
    Runtime(NativeError),
}

#[derive(Debug)]
pub struct DecodedFrame {
    pub tracking_valid: bool,
    pub detections: Vec<CurrentDetection>,
    pub evidence: Vec<RestorationEvidence>,
}

/// Header: "MDFR", u16 version=1, u8 detection count, u8 pair count,
/// u8 tracking (0/1), three reserved zero bytes.
/// Detection (53 bytes): u32 ID, six f64 ray coordinates, u8 occlusion.
/// Pair (33 bytes): u32 saved ID, u32 current ID, three f64 evidence scores,
/// u8 orientation-aligned. IDs must fit positive Kotlin Int values.
pub fn decode_frame_packet(bytes: &[u8]) -> Result<DecodedFrame, FrameDecodeError> {
    if bytes.len() > MAX_FRAME_PACKET_BYTES {
        return Err(FrameDecodeError::TooLarge);
    }
    let mut reader = Reader(bytes);
    if reader.take::<4>()? != *b"MDFR" {
        return Err(FrameDecodeError::InvalidMagic);
    }
    if u16::from_be_bytes(reader.take()?) != 1 {
        return Err(FrameDecodeError::UnsupportedVersion);
    }
    let detection_count = reader.byte()? as usize;
    let pair_count = reader.byte()? as usize;
    if detection_count > 5 || pair_count > 25 {
        return Err(FrameDecodeError::InvalidCount);
    }
    let tracking_valid = reader.boolean()?;
    if reader.take::<3>()? != [0; 3] {
        return Err(FrameDecodeError::ReservedBits);
    }
    if bytes.len() != 12 + 53 * detection_count + 33 * pair_count {
        return Err(FrameDecodeError::InvalidLength);
    }
    let mut detections = Vec::with_capacity(detection_count);
    for _ in 0..detection_count {
        detections.push(CurrentDetection {
            current_id: reader.id()?,
            ray: WorldRay { origin: reader.vector()?, direction: reader.vector()? },
            occludes_other: reader.boolean()?,
        });
    }
    let mut evidence = Vec::with_capacity(pair_count);
    for _ in 0..pair_count {
        let saved_id = reader.id()?;
        let current_id = reader.id()?;
        let score = PairScore::new(reader.number()?, reader.number()?, reader.number()?)
            .map_err(|_| FrameDecodeError::InvalidScore)?;
        evidence.push(RestorationEvidence {
            pair: PairEvidence { saved_id, current_id, score },
            orientation_aligned: reader.boolean()?,
        });
    }
    Ok(DecodedFrame { tracking_valid, detections, evidence })
}

/// Decode errors invalidate the addressed session before returning. Clock
/// regression also fails closed in the underlying core; no fake timestamp or
/// replacement position is fabricated. Unknown handles affect no other session.
pub fn apply_frame_packet(
    runtime: &mut NativeRuntime,
    handle: SessionHandle,
    observed_at_ms: u64,
    bytes: &[u8],
) -> Result<RestoreState, FrameIngressError> {
    runtime.state(handle, observed_at_ms).map_err(FrameIngressError::Runtime)?;
    let frame = match decode_frame_packet(bytes) {
        Ok(frame) => frame,
        Err(error) => {
            let _ = runtime.update_frame(handle, observed_at_ms, false, &[], &[]);
            return Err(FrameIngressError::Decode(error));
        }
    };
    runtime.update_frame(
        handle, observed_at_ms, frame.tracking_valid, &frame.detections, &frame.evidence,
    ).map_err(FrameIngressError::Runtime)
}

struct Reader<'a>(&'a [u8]);

impl Reader<'_> {
    fn take<const N: usize>(&mut self) -> Result<[u8; N], FrameDecodeError> {
        let part = self.0.get(..N).ok_or(FrameDecodeError::Truncated)?;
        let value = part.try_into().map_err(|_| FrameDecodeError::Truncated)?;
        self.0 = &self.0[N..];
        Ok(value)
    }

    fn byte(&mut self) -> Result<u8, FrameDecodeError> {
        Ok(self.take::<1>()?[0])
    }

    fn boolean(&mut self) -> Result<bool, FrameDecodeError> {
        match self.byte()? {
            0 => Ok(false),
            1 => Ok(true),
            _ => Err(FrameDecodeError::InvalidBoolean),
        }
    }

    fn id(&mut self) -> Result<u32, FrameDecodeError> {
        let id = u32::from_be_bytes(self.take()?);
        if id == 0 || id > i32::MAX as u32 {
            return Err(FrameDecodeError::InvalidId);
        }
        Ok(id)
    }

    fn number(&mut self) -> Result<f64, FrameDecodeError> {
        let value = f64::from_be_bytes(self.take()?);
        if !value.is_finite() {
            return Err(FrameDecodeError::InvalidNumber);
        }
        Ok(value)
    }

    fn vector(&mut self) -> Result<WorldVector, FrameDecodeError> {
        Ok(WorldVector { x: self.number()?, y: self.number()?, z: self.number()? })
    }
}
