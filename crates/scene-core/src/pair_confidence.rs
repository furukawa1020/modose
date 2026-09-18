//! Validated evidence scores. This module does not infer physical presence.

pub const ACCEPT_SCORE: f64 = 0.78;
pub const AMBIGUOUS_SCORE: f64 = 0.62;
pub const MIN_MATCH_MARGIN: f64 = 0.08;

#[derive(Debug, Clone, Copy, PartialEq, Eq)]
pub enum EvidenceField {
    VlmConfidence,
    NormalizedEmbedding,
    SignatureCompatibility,
}

#[derive(Debug, Clone, Copy, PartialEq, Eq)]
pub struct InvalidEvidence {
    pub field: EvidenceField,
}

#[derive(Debug, Clone, Copy, PartialEq, Eq)]
pub enum ScoreBand {
    Rejected,
    Ambiguous,
    Eligible,
}

/// Eligible is not a confirmed match: assignment and ambiguity gates must follow.
#[derive(Debug, Clone, Copy, PartialEq)]
pub struct PairScore(f64);

impl PairScore {
    /// All inputs must already be normalized to [0, 1].
    /// Raw cosine similarity must not be passed as normalized embedding evidence.
    pub fn new(
        vlm_confidence: f64,
        normalized_embedding: f64,
        signature_compatibility: f64,
    ) -> Result<Self, InvalidEvidence> {
        for (field, value) in [
            (EvidenceField::VlmConfidence, vlm_confidence),
            (EvidenceField::NormalizedEmbedding, normalized_embedding),
            (EvidenceField::SignatureCompatibility, signature_compatibility),
        ] {
            if !value.is_finite() || !(0.0..=1.0).contains(&value) {
                return Err(InvalidEvidence { field });
            }
        }
        Ok(Self(
            0.50 * vlm_confidence
                + 0.35 * normalized_embedding
                + 0.15 * signature_compatibility,
        ))
    }

    pub fn value(self) -> f64 {
        self.0
    }

    pub fn band(self) -> ScoreBand {
        if self.0 >= ACCEPT_SCORE {
            ScoreBand::Eligible
        } else if self.0 >= AMBIGUOUS_SCORE {
            ScoreBand::Ambiguous
        } else {
            ScoreBand::Rejected
        }
    }
}
