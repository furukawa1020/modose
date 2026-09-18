//! World rays to anchor-relative tabletop coordinates. Units are meters.

use crate::local_restoration::TablePosition;

#[derive(Debug, Clone, Copy, PartialEq)]
pub struct WorldVector {
    pub x: f64,
    pub y: f64,
    pub z: f64,
}

impl WorldVector {
    fn finite(self) -> bool {
        self.x.is_finite() && self.y.is_finite() && self.z.is_finite()
    }

    fn dot(self, other: Self) -> f64 {
        self.x * other.x + self.y * other.y + self.z * other.z
    }

    fn norm(self) -> f64 {
        self.x.hypot(self.y).hypot(self.z)
    }

    fn cross(self, other: Self) -> Self {
        Self {
            x: self.y * other.z - self.z * other.y,
            y: self.z * other.x - self.x * other.z,
            z: self.x * other.y - self.y * other.x,
        }
    }
}

#[derive(Debug, Clone, Copy)]
pub struct WorldRay {
    pub origin: WorldVector,
    pub direction: WorldVector,
}

#[derive(Debug, Clone, Copy, PartialEq, Eq)]
pub enum ProjectionError {
    InvalidCoordinates,
    InvalidBasis,
    NonHorizontalPlane,
    InvalidPolygon,
    InvalidRay,
    ParallelRay,
    BehindRay,
    OutsidePlane,
}

#[derive(Debug)]
pub struct TablePlane {
    origin: WorldVector,
    x_axis: WorldVector,
    z_axis: WorldVector,
    normal: WorldVector,
    polygon: Vec<TablePosition>,
    winding: f64,
}

impl TablePlane {
    pub fn new(
        origin: WorldVector,
        x_axis: WorldVector,
        z_axis: WorldVector,
        polygon: &[TablePosition],
    ) -> Result<Self, ProjectionError> {
        if !origin.finite() || !x_axis.finite() || !z_axis.finite() {
            return Err(ProjectionError::InvalidCoordinates);
        }
        if (x_axis.norm() - 1.0).abs() > 1e-6
            || (z_axis.norm() - 1.0).abs() > 1e-6
            || x_axis.dot(z_axis).abs() > 1e-6
        {
            return Err(ProjectionError::InvalidBasis);
        }
        let normal = x_axis.cross(z_axis);
        if normal.y.abs() / normal.norm() < 0.999 {
            return Err(ProjectionError::NonHorizontalPlane);
        }
        if !(3..=64).contains(&polygon.len())
            || polygon.iter().any(|point| !point.is_finite())
            || polygon.iter().enumerate().any(|(i, point)| polygon[..i].contains(point))
        {
            return Err(ProjectionError::InvalidPolygon);
        }
        let area: f64 = (0..polygon.len()).map(|i| {
            let a = polygon[i];
            let b = polygon[(i + 1) % polygon.len()];
            a.x * b.z - a.z * b.x
        }).sum();
        if !area.is_finite() || area.abs() <= 1e-12 {
            return Err(ProjectionError::InvalidPolygon);
        }
        let winding = area.signum();
        // All vertices must lie on the interior side of every boundary edge.
        // This also rejects concave and self-intersecting orderings.
        for i in 0..polygon.len() {
            let a = polygon[i];
            let b = polygon[(i + 1) % polygon.len()];
            if polygon.iter().any(|&point| {
                let side = edge_side(a, b, point) * winding;
                !side.is_finite() || side < 0.0
            }) {
                return Err(ProjectionError::InvalidPolygon);
            }
        }
        Ok(Self {
            origin, x_axis, z_axis, normal,
            polygon: polygon.to_vec(),
            winding,
        })
    }

    /// Includes the boundary; non-finite arithmetic is never treated as inside.
    pub fn contains(&self, point: TablePosition) -> bool {
        point.is_finite() && (0..self.polygon.len()).all(|i| {
            let side = edge_side(
                self.polygon[i],
                self.polygon[(i + 1) % self.polygon.len()],
                point,
            ) * self.winding;
            side.is_finite() && side >= 0.0
        })
    }

    pub fn project(&self, ray: WorldRay) -> Result<TablePosition, ProjectionError> {
        let length = ray.direction.norm();
        if !ray.origin.finite() || !ray.direction.finite()
            || !length.is_finite() || length <= 0.0
        {
            return Err(ProjectionError::InvalidRay);
        }
        let direction = WorldVector {
            x: ray.direction.x / length,
            y: ray.direction.y / length,
            z: ray.direction.z / length,
        };
        let denominator = direction.dot(self.normal);
        if denominator.abs() < 1e-6 {
            return Err(ProjectionError::ParallelRay);
        }
        let offset = WorldVector {
            x: ray.origin.x - self.origin.x,
            y: ray.origin.y - self.origin.y,
            z: ray.origin.z - self.origin.z,
        };
        let distance = -offset.dot(self.normal) / denominator;
        if !distance.is_finite() {
            return Err(ProjectionError::InvalidCoordinates);
        }
        if distance < 0.0 {
            return Err(ProjectionError::BehindRay);
        }
        let relative = WorldVector {
            x: offset.x + direction.x * distance,
            y: offset.y + direction.y * distance,
            z: offset.z + direction.z * distance,
        };
        let position = TablePosition {
            x: relative.dot(self.x_axis),
            z: relative.dot(self.z_axis),
        };
        if !relative.finite() || !position.is_finite() {
            return Err(ProjectionError::InvalidCoordinates);
        }
        if !self.contains(position) {
            return Err(ProjectionError::OutsidePlane);
        }
        Ok(position)
    }
}

fn edge_side(a: TablePosition, b: TablePosition, point: TablePosition) -> f64 {
    (b.x - a.x) * (point.z - a.z) - (b.z - a.z) * (point.x - a.x)
}
