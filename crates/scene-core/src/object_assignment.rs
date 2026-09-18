//! Bounded one-to-one matching with explicit unmatched nodes and ambiguity gates.

use crate::pair_confidence::{
    PairScore, ACCEPT_SCORE, AMBIGUOUS_SCORE, MIN_MATCH_MARGIN,
};

#[derive(Debug, Clone, Copy)]
pub struct PairEvidence {
    pub saved_id: u32,
    pub current_id: u32,
    pub score: PairScore,
}

#[derive(Debug, Clone, Copy, PartialEq)]
pub enum MatchDecision {
    Accepted { current_id: u32, score: f64 },
    Ambiguous,
    // Lack of an accepted match is not proof that a physical object is missing.
    Unmatched,
}

#[derive(Debug, Clone, Copy, PartialEq)]
pub struct ObjectMatch {
    pub saved_id: u32,
    pub decision: MatchDecision,
}

#[derive(Debug, Clone, Copy, PartialEq, Eq)]
pub enum AssignmentError {
    InvalidObjectCount,
    InvalidObjectIds,
    TooManyPairs,
    UnknownPairObject,
    DuplicatePair,
}

/// Returns matches sorted by saved ID, independent of caller input order.
/// Ambiguous candidates never expose a current ID suitable for guidance.
pub fn assign_objects(
    saved_ids: &[u32],
    current_ids: &[u32],
    evidence: &[PairEvidence],
) -> Result<Vec<ObjectMatch>, AssignmentError> {
    if !(1..=5).contains(&saved_ids.len()) || current_ids.len() > 5 {
        return Err(AssignmentError::InvalidObjectCount);
    }
    if evidence.len() > 25 {
        return Err(AssignmentError::TooManyPairs);
    }
    let mut saved = saved_ids.to_vec();
    let mut current = current_ids.to_vec();
    saved.sort_unstable();
    current.sort_unstable();
    for ids in [&saved, &current] {
        if ids.contains(&0) || ids.windows(2).any(|pair| pair[0] == pair[1]) {
            return Err(AssignmentError::InvalidObjectIds);
        }
    }
    let mut scores = vec![vec![None; current.len()]; saved.len()];
    for pair in evidence {
        let row = saved.binary_search(&pair.saved_id)
            .map_err(|_| AssignmentError::UnknownPairObject)?;
        let col = current.binary_search(&pair.current_id)
            .map_err(|_| AssignmentError::UnknownPairObject)?;
        if scores[row][col].replace(pair.score.value()).is_some() {
            return Err(AssignmentError::DuplicatePair);
        }
    }

    // One dummy per saved object permits every row to remain unmatched.
    let weights: Vec<Vec<f64>> = scores.iter().map(|row| {
        let mut weights: Vec<f64> = row.iter().map(|score| {
            score.filter(|value| *value >= AMBIGUOUS_SCORE).unwrap_or(0.0)
        }).collect();
        weights.extend(std::iter::repeat_n(AMBIGUOUS_SCORE, saved.len()));
        weights
    }).collect();
    let (selected, optimum) = hungarian(&weights, None);
    let mut matches = Vec::with_capacity(saved.len());
    for (row, &saved_id) in saved.iter().enumerate() {
        let col = selected[row];
        let decision = if col >= current.len() {
            if scores[row].iter().flatten().any(|score| *score >= AMBIGUOUS_SCORE) {
                MatchDecision::Ambiguous
            } else {
                MatchDecision::Unmatched
            }
        } else {
            let score = weights[row][col];
            let runner_up = scores[row].iter().enumerate()
                .filter(|(other, _)| *other != col)
                .filter_map(|(_, value)| *value)
                .fold(AMBIGUOUS_SCORE, f64::max);
            // Re-solve without this edge: a near-equal alternative means the
            // globally selected identity is not reliable enough to guide.
            let (_, alternative) = hungarian(&weights, Some((row, col)));
            if score < ACCEPT_SCORE
                || score - runner_up < MIN_MATCH_MARGIN
                || optimum - alternative < MIN_MATCH_MARGIN
            {
                MatchDecision::Ambiguous
            } else {
                MatchDecision::Accepted { current_id: current[col], score }
            }
        };
        matches.push(ObjectMatch { saved_id, decision });
    }
    Ok(matches)
}

/// Rectangular Hungarian minimization using cost = 1 - weight.
/// Internal inputs have 1..=5 rows, at least as many columns, finite [0,1]
/// weights and a dummy for every row. Real edges can be forbidden at weight 0.
fn hungarian(
    weights: &[Vec<f64>],
    forbidden: Option<(usize, usize)>,
) -> (Vec<usize>, f64) {
    let rows = weights.len();
    let cols = weights[0].len();
    let weight = |row: usize, col: usize| {
        if forbidden == Some((row, col)) { 0.0 } else { weights[row][col] }
    };
    let mut u = vec![0.0; rows + 1];
    let mut v = vec![0.0; cols + 1];
    let mut owner = vec![0usize; cols + 1];
    let mut way = vec![0usize; cols + 1];
    for row in 1..=rows {
        owner[0] = row;
        let mut col = 0;
        let mut min_cost = vec![f64::INFINITY; cols + 1];
        let mut used = vec![false; cols + 1];
        loop {
            used[col] = true;
            let active_row = owner[col];
            let mut delta = f64::INFINITY;
            let mut next_col = 0;
            for candidate in 1..=cols {
                if used[candidate] {
                    continue;
                }
                let reduced = 1.0 - weight(active_row - 1, candidate - 1)
                    - u[active_row] - v[candidate];
                if reduced < min_cost[candidate] {
                    min_cost[candidate] = reduced;
                    way[candidate] = col;
                }
                if min_cost[candidate] < delta {
                    delta = min_cost[candidate];
                    next_col = candidate;
                }
            }
            for candidate in 0..=cols {
                if used[candidate] {
                    u[owner[candidate]] += delta;
                    v[candidate] -= delta;
                } else {
                    min_cost[candidate] -= delta;
                }
            }
            col = next_col;
            if owner[col] == 0 {
                break;
            }
        }
        while col != 0 {
            let previous = way[col];
            owner[col] = owner[previous];
            col = previous;
        }
    }
    let mut selected = vec![0; rows];
    for col in 1..=cols {
        if owner[col] != 0 {
            selected[owner[col] - 1] = col - 1;
        }
    }
    let total = selected.iter().enumerate()
        .map(|(row, &col)| weight(row, col))
        .sum();
    (selected, total)
}
