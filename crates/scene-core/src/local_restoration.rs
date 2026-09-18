//! Per-object restoration evidence in anchor-relative meters and monotonic ms.

pub const ALIGN_DISTANCE_M: f64 = 0.03;
pub const RELEASE_DISTANCE_M: f64 = 0.05;
pub const STABLE_DURATION_MS: u64 = 800;
// Conservative evidence freshness policy; requires device validation.
pub const MAX_OBSERVATION_GAP_MS: u64 = 200;

#[derive(Debug, Clone, Copy, PartialEq)]
pub struct TablePosition {
    pub x: f64,
    pub z: f64,
}

impl TablePosition {
    pub fn is_finite(self) -> bool {
        self.x.is_finite() && self.z.is_finite()
    }
}

#[derive(Debug, Clone, Copy, PartialEq)]
pub enum Observation {
    Unobserved,
    Missing,
    Ambiguous,
    TrackingLost,
    // The caller must establish valid tracking and unambiguous identity.
    Located {
        position: TablePosition,
        orientation_aligned: bool,
    },
}

#[derive(Debug, Clone, Copy, PartialEq, Eq)]
pub enum LocalState {
    Unobserved,
    Missing,
    Ambiguous,
    TrackingLost,
    Located,
    Stabilizing,
    Aligned,
}

#[derive(Debug, Clone, Copy, PartialEq, Eq)]
pub enum LocalError {
    InvalidPosition,
    NonIncreasingTimestamp,
}

#[derive(Debug)]
pub struct LocalRestoration {
    target: TablePosition,
    state: LocalState,
    stable_since_ms: Option<u64>,
    last_observed_ms: Option<u64>,
}

impl LocalRestoration {
    pub fn new(target: TablePosition) -> Result<Self, LocalError> {
        if !target.is_finite() {
            return Err(LocalError::InvalidPosition);
        }
        Ok(Self {
            target,
            state: LocalState::Unobserved,
            stable_since_ms: None,
            last_observed_ms: None,
        })
    }

    pub fn observe(
        &mut self,
        now_ms: u64,
        observation: Observation,
    ) -> Result<LocalState, LocalError> {
        if let Some(previous) = self.last_observed_ms {
            if now_ms <= previous {
                self.invalidate();
                return Err(LocalError::NonIncreasingTimestamp);
            }
            if now_ms - previous > MAX_OBSERVATION_GAP_MS {
                self.invalidate();
            }
        }
        self.last_observed_ms = Some(now_ms);
        let (position, orientation_aligned) = match observation {
            Observation::Located { position, orientation_aligned } => {
                (position, orientation_aligned)
            }
            unavailable => {
                self.stable_since_ms = None;
                self.state = match unavailable {
                    Observation::Missing => LocalState::Missing,
                    Observation::Ambiguous => LocalState::Ambiguous,
                    Observation::TrackingLost => LocalState::TrackingLost,
                    _ => LocalState::Unobserved,
                };
                return Ok(self.state);
            }
        };
        let distance = (position.x - self.target.x).hypot(position.z - self.target.z);
        if !position.is_finite() || !distance.is_finite() {
            self.invalidate();
            return Err(LocalError::InvalidPosition);
        }
        if !orientation_aligned {
            self.stable_since_ms = None;
            self.state = LocalState::Located;
        } else if self.state == LocalState::Aligned && distance < RELEASE_DISTANCE_M {
            // Preserve alignment only inside the outer hysteresis boundary.
        } else if distance <= ALIGN_DISTANCE_M {
            let since = *self.stable_since_ms.get_or_insert(now_ms);
            self.state = if now_ms - since >= STABLE_DURATION_MS {
                LocalState::Aligned
            } else {
                LocalState::Stabilizing
            };
        } else {
            self.stable_since_ms = None;
            self.state = LocalState::Located;
        }
        Ok(self.state)
    }

    /// Historical state, not a freshness guarantee. Use is_aligned_at for gates.
    pub fn state(&self) -> LocalState {
        self.state
    }

    pub fn is_aligned_at(&self, now_ms: u64) -> bool {
        self.state == LocalState::Aligned
            && self.last_observed_ms.is_some_and(|observed| {
                now_ms >= observed && now_ms - observed <= MAX_OBSERVATION_GAP_MS
            })
    }

    pub(crate) fn invalidate(&mut self) {
        self.state = LocalState::Unobserved;
        self.stable_since_ms = None;
    }
}
