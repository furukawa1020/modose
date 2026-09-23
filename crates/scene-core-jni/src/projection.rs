//! Stateless, all-or-nothing projection of 1..5 world rays into table meters.
use jni::objects::{JDoubleArray, JObject};
use jni::sys::jdoubleArray;
use jni::JNIEnv;
use scene_core::table_projection::{WorldRay, WorldVector};

use super::{boundary, doubles, plane, Failure};

fn project(geometry: &[f64], rays: &[f64]) -> Result<Vec<f64>, Failure> {
    if !(6..=30).contains(&rays.len()) || rays.len() % 6 != 0 {
        return Err("Invalid projection ray count");
    }
    let table = plane(geometry)?;
    let mut targets = Vec::with_capacity(rays.len() / 3);
    for ray in rays.chunks_exact(6) {
        let vector = |i| WorldVector { x: ray[i], y: ray[i + 1], z: ray[i + 2] };
        let target = table.project(WorldRay { origin: vector(0), direction: vector(3) })
            .map_err(|_| "Baseline ray cannot be projected onto the table")?;
        targets.extend_from_slice(&[target.x, target.z]);
    }
    Ok(targets)
}

// SAFETY: Matches nativeProjectTargets(DoubleArray, DoubleArray): DoubleArray.
// No Java references, native handles or partially projected results are retained.
#[unsafe(no_mangle)]
pub extern "system" fn Java_com_modose_app_core_NativeSceneBindings_nativeProjectTargets(
    mut env: JNIEnv<'_>, _owner: JObject<'_>,
    geometry: JDoubleArray<'_>, rays: JDoubleArray<'_>,
) -> jdoubleArray {
    boundary(&mut env, std::ptr::null_mut(), |env| {
        let geometry = doubles(env, &geometry, 15, 137)?;
        let rays = doubles(env, &rays, 6, 30)?;
        let values = project(&geometry, &rays)?;
        let array = env.new_double_array(values.len() as i32)
            .map_err(|_| "Target allocation failed")?;
        env.set_double_array_region(&array, 0, &values)
            .map_err(|_| "Target copy failed")?;
        Ok(array.into_raw())
    })
}
