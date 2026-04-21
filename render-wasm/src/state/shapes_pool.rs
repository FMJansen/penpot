use std::collections::HashMap;
use std::iter;

use crate::performance;
use crate::shapes;
use crate::shapes::Shape;
use crate::uuid::Uuid;

use crate::shapes::StructureEntry;
use crate::skia;

use std::cell::OnceCell;

use crate::math;
use crate::math::bools as math_bools;
use crate::math::Matrix;

const SHAPES_POOL_ALLOC_MULTIPLIER: f32 = 1.3;

/// A pool allocator for `Shape` objects that attempts to minimize memory reallocations.
///
/// `ShapesPoolImpl` pre-allocates a contiguous vector of `Shape` instances,
/// which can be reused and indexed efficiently. This design helps avoid
/// memory reallocation overhead by reserving enough space in advance.
///
/// # Memory Layout
///
/// Shapes are stored in a `Vec<Shape>`, which keeps the `Shape` instances
/// in a contiguous memory block.
///
/// # Index-based Design
///
/// All auxiliary HashMaps (modifiers, structure, scale_content, modified_shape_cache)
/// use `usize` indices instead of `&'a Uuid` references. This eliminates:
/// - Unsafe lifetime extensions
/// - The need for `rebuild_references()` after Vec reallocation
/// - Complex lifetime annotations
///
/// The `uuid_to_idx` HashMap maps `Uuid` (owned) to indices, avoiding lifetime issues.
///
pub struct ShapesPoolImpl {
    shapes: Vec<Shape>,
    counter: usize,

    /// Maps UUID to index in the shapes Vec. Uses owned Uuid, no lifetime needed.
    uuid_to_idx: HashMap<Uuid, usize>,

    /// Cache for modified shapes, keyed by index
    modified_shape_cache: HashMap<usize, OnceCell<Shape>>,
    /// Transform modifiers, keyed by index
    modifiers: HashMap<usize, skia::Matrix>,
    /// Structure entries, keyed by index
    structure: HashMap<usize, Vec<StructureEntry>>,
    /// Scale content values, keyed by index
    scale_content: HashMap<usize, f32>,

    /// Monotonically increasing version number per shape index.
    ///
    /// Bumped every time a shape is accessed mutably via `get_mut`, and
    /// the increment is propagated up the parent chain so the
    /// retained-mode renderer can detect "this shape or any of its
    /// descendants changed" by comparing the version of a top-level
    /// ancestor against the version cached when its texture was
    /// captured.
    ///
    /// Modifiers are intentionally *not* tracked here: they are applied
    /// as a canvas transform by the retained compositor and must not
    /// invalidate the cached texture.
    shape_versions: Vec<u64>,
}

// Type aliases - no longer need lifetimes!
pub type ShapesPool = ShapesPoolImpl;
pub type ShapesPoolRef<'a> = &'a ShapesPoolImpl;
pub type ShapesPoolMutRef<'a> = &'a mut ShapesPoolImpl;

impl ShapesPoolImpl {
    pub fn new() -> Self {
        ShapesPoolImpl {
            shapes: vec![],
            counter: 0,
            uuid_to_idx: HashMap::default(),

            modified_shape_cache: HashMap::default(),
            modifiers: HashMap::default(),
            structure: HashMap::default(),
            scale_content: HashMap::default(),
            shape_versions: vec![],
        }
    }

    pub fn initialize(&mut self, capacity: usize) {
        performance::begin_measure!("shapes_pool_initialize");
        self.counter = 0;
        self.uuid_to_idx = HashMap::with_capacity(capacity);

        let additional = capacity as i32 - self.shapes.len() as i32;
        if additional <= 0 {
            return;
        }

        // Reserve extra capacity to avoid future reallocations
        let target_capacity = (capacity as f32 * SHAPES_POOL_ALLOC_MULTIPLIER) as usize;
        self.shapes
            .reserve_exact(target_capacity.saturating_sub(self.shapes.len()));

        self.shapes
            .extend(iter::repeat_with(|| Shape::new(Uuid::nil())).take(additional as usize));
        self.shape_versions.resize(self.shapes.len(), 0);
        performance::end_measure!("shapes_pool_initialize");
    }

    pub fn add_shape(&mut self, id: Uuid) -> &mut Shape {
        if self.counter >= self.shapes.len() {
            // We need more space
            let current_capacity = self.shapes.capacity();
            // Ensure we add at least 1 shape when the pool is empty
            let additional =
                ((self.shapes.len() as f32 * SHAPES_POOL_ALLOC_MULTIPLIER) as usize).max(1);
            let needed_capacity = self.shapes.len() + additional;

            if needed_capacity > current_capacity {
                // Reserve extra space to minimize future reallocations
                let extra_reserve = (needed_capacity as f32 * 0.5) as usize;
                self.shapes
                    .reserve(needed_capacity + extra_reserve - current_capacity);
            }

            self.shapes
                .extend(iter::repeat_with(|| Shape::new(Uuid::nil())).take(additional));
        }
        if self.shape_versions.len() < self.shapes.len() {
            self.shape_versions.resize(self.shapes.len(), 0);
        }

        let idx = self.counter;
        let new_shape = &mut self.shapes[idx];
        new_shape.id = id;

        // Simply store the UUID -> index mapping. No unsafe lifetime tricks needed!
        self.uuid_to_idx.insert(id, idx);
        self.counter += 1;

        // Bump version so a late retained-mode render doesn't serve a
        // stale texture from a shape uuid that has been reused.
        if let Some(v) = self.shape_versions.get_mut(idx) {
            *v = v.wrapping_add(1);
        }

        &mut self.shapes[idx]
    }
    // No longer needed! Index-based storage means no references to rebuild.
    // The old rebuild_references() function has been removed entirely.

    pub fn len(&self) -> usize {
        self.uuid_to_idx.len()
    }

    pub fn has(&self, id: &Uuid) -> bool {
        self.uuid_to_idx.contains_key(id)
    }

    pub fn get_mut(&mut self, id: &Uuid) -> Option<&mut Shape> {
        let idx = *self.uuid_to_idx.get(id)?;
        self.bump_version_with_ancestors(idx);
        Some(&mut self.shapes[idx])
    }

    /// Increment the version counter of `idx` and walk up its parent
    /// chain bumping each ancestor as well. The retained-mode cache
    /// stores textures per top-level shape (direct child of the root);
    /// propagating lets a deep mutation correctly invalidate whichever
    /// ancestor owns the cached subtree.
    fn bump_version_with_ancestors(&mut self, idx: usize) {
        if let Some(v) = self.shape_versions.get_mut(idx) {
            *v = v.wrapping_add(1);
        }

        // The root (Uuid::nil) has no parent; walking stops naturally
        // via `parent_id == None`.
        let mut current_idx = idx;
        loop {
            let parent_id = match self.shapes.get(current_idx).and_then(|s| s.parent_id) {
                Some(pid) => pid,
                None => break,
            };
            let Some(parent_idx) = self.uuid_to_idx.get(&parent_id).copied() else {
                break;
            };
            if parent_idx == current_idx {
                // Defensive: a self-parent would loop forever.
                break;
            }
            if let Some(v) = self.shape_versions.get_mut(parent_idx) {
                *v = v.wrapping_add(1);
            }
            current_idx = parent_idx;
        }
    }

    /// Current version of a shape. Used by the retained-mode texture
    /// cache to detect invalidated entries: captured_version != current
    /// ⇒ re-rasterize.
    ///
    /// Returns `None` for uuids that are not (or no longer) registered
    /// in the pool.
    pub fn shape_version(&self, id: &Uuid) -> Option<u64> {
        let idx = *self.uuid_to_idx.get(id)?;
        self.shape_versions.get(idx).copied()
    }

    /// Raw transform modifier associated with `id`, without applying
    /// it to the shape. The retained-mode compositor uses this to
    /// concatenate the modifier onto the canvas before blitting the
    /// cached texture, so the workspace behaves like the SVG renderer
    /// (texture stays put, layer transform moves).
    pub fn modifier_of(&self, id: &Uuid) -> Option<skia::Matrix> {
        let idx = *self.uuid_to_idx.get(id)?;
        self.modifiers.get(&idx).copied()
    }

    /// Removes every active modifier and returns them keyed by Uuid,
    /// ready to be handed back to `set_modifiers`. Used by the
    /// retained-mode capture step to ensure the snapshot is taken at
    /// the shape's base position — modifiers are re-applied later as
    /// canvas transforms, so capturing with them applied would
    /// double-transform the shape.
    pub fn take_modifiers(&mut self) -> HashMap<Uuid, skia::Matrix> {
        if self.modifiers.is_empty() {
            return HashMap::new();
        }
        let mut out = HashMap::with_capacity(self.modifiers.len());
        for (idx, matrix) in self.modifiers.drain() {
            if let Some(shape) = self.shapes.get(idx) {
                out.insert(shape.id, matrix);
            }
        }
        // Modified-shape cache entries baked in the taken modifiers
        // become stale; drop them so subsequent `get()` calls return
        // the un-transformed shape.
        self.modified_shape_cache.clear();
        out
    }

    /// Get a shape by UUID. Returns the modified shape if modifiers/structure
    /// are applied, otherwise returns the base shape.
    pub fn get(&self, id: &Uuid) -> Option<&Shape> {
        let idx = *self.uuid_to_idx.get(id)?;

        let shape = &self.shapes[idx];

        // Check if this shape needs modification (has modifiers, structure changes, or is a bool)
        let needs_modification = shape.is_bool()
            || self.modifiers.contains_key(&idx)
            || self.structure.contains_key(&idx)
            || self.scale_content.contains_key(&idx);

        if needs_modification {
            // Check if we have a cached modified version
            if let Some(cell) = self.modified_shape_cache.get(&idx) {
                Some(cell.get_or_init(|| {
                    let mut modified_shape =
                        shape.transformed(self.modifiers.get(&idx), self.structure.get(&idx));

                    if self.to_update_bool(&modified_shape) {
                        math_bools::update_bool_to_path(&mut modified_shape, self);
                    }

                    if let Some(scale) = self.scale_content.get(&idx) {
                        modified_shape.scale_content(*scale);
                    }
                    modified_shape
                }))
            } else {
                Some(shape)
            }
        } else {
            Some(shape)
        }
    }

    // Given an id, returns the depth in the tree-shaped structure
    // of shapes.
    pub fn get_depth(&self, id: &Uuid) -> usize {
        if id == &Uuid::nil() {
            return 0;
        }

        let Some(idx) = self.uuid_to_idx.get(id) else {
            return 0;
        };

        let shape = &self.shapes[*idx];

        let Some(parent_id) = shape.parent_id else {
            return 0;
        };

        self.get_depth(&parent_id) + 1
    }

    #[allow(dead_code)]
    pub fn iter(&self) -> std::slice::Iter<'_, Shape> {
        self.shapes.iter()
    }

    #[allow(dead_code)]
    pub fn iter_mut(&mut self) -> std::slice::IterMut<'_, Shape> {
        self.shapes.iter_mut()
    }

    fn clean_shape_cache(&mut self) {
        self.modified_shape_cache.clear()
    }

    pub fn set_modifiers(&mut self, modifiers: HashMap<Uuid, skia::Matrix>) {
        // Convert HashMap<Uuid, V> to HashMap<usize, V> using indices
        // Initialize the cache cells for affected shapes

        let mut ids = Vec::<Uuid>::new();
        let mut modifiers_with_idx = HashMap::with_capacity(modifiers.len());

        for (uuid, matrix) in modifiers {
            if let Some(idx) = self.uuid_to_idx.get(&uuid).copied() {
                modifiers_with_idx.insert(idx, matrix);
                ids.push(uuid);
            }
        }
        self.modifiers = modifiers_with_idx;

        let all_ids = shapes::all_with_ancestors(&ids, self, true);
        for uuid in all_ids {
            if let Some(idx) = self.uuid_to_idx.get(&uuid).copied() {
                self.modified_shape_cache.insert(idx, OnceCell::new());
            }
        }
    }

    pub fn set_structure(&mut self, structure: HashMap<Uuid, Vec<StructureEntry>>) {
        // Convert HashMap<Uuid, V> to HashMap<usize, V> using indices
        // Initialize the cache cells for affected shapes
        let mut structure_with_idx = HashMap::with_capacity(structure.len());
        let mut ids = Vec::<Uuid>::new();

        for (uuid, entries) in structure {
            if let Some(idx) = self.uuid_to_idx.get(&uuid).copied() {
                structure_with_idx.insert(idx, entries);
                ids.push(uuid);
            }
        }
        self.structure = structure_with_idx;

        let all_ids = shapes::all_with_ancestors(&ids, self, true);
        for uuid in all_ids {
            if let Some(idx) = self.uuid_to_idx.get(&uuid).copied() {
                self.modified_shape_cache.insert(idx, OnceCell::new());
            }
        }
    }

    pub fn set_scale_content(&mut self, scale_content: HashMap<Uuid, f32>) {
        // Convert HashMap<Uuid, V> to HashMap<usize, V> using indices
        // Initialize the cache cells for affected shapes
        let mut scale_content_with_idx = HashMap::with_capacity(scale_content.len());
        let mut ids = Vec::<Uuid>::new();

        for (uuid, value) in scale_content {
            if let Some(idx) = self.uuid_to_idx.get(&uuid).copied() {
                scale_content_with_idx.insert(idx, value);
                ids.push(uuid);
            }
        }
        self.scale_content = scale_content_with_idx;

        let all_ids = shapes::all_with_ancestors(&ids, self, true);
        for uuid in all_ids {
            if let Some(idx) = self.uuid_to_idx.get(&uuid).copied() {
                self.modified_shape_cache.insert(idx, OnceCell::new());
            }
        }
    }

    /// Clears transient per-frame state (modifiers, structure, scale_content)
    /// and returns the list of UUIDs that had a `modifier` applied at the
    /// moment of cleaning. The caller can use that list to re-sync the tile
    /// index / tile cache for those shapes: after cleaning their modifier is
    /// gone, but if we don't touch their tiles they keep pointing at the
    /// previous modified position and the tile texture cache may serve stale
    /// pixels.
    pub fn clean_all(&mut self) -> Vec<Uuid> {
        self.clean_shape_cache();

        let modified_uuids: Vec<Uuid> = if self.modifiers.is_empty() {
            Vec::new()
        } else {
            let mut idx_to_uuid: HashMap<usize, Uuid> =
                HashMap::with_capacity(self.uuid_to_idx.len());
            for (uuid, idx) in self.uuid_to_idx.iter() {
                idx_to_uuid.insert(*idx, *uuid);
            }
            self.modifiers
                .keys()
                .filter_map(|idx| idx_to_uuid.get(idx).copied())
                .collect()
        };

        self.modifiers = HashMap::default();
        self.structure = HashMap::default();
        self.scale_content = HashMap::default();

        modified_uuids
    }

    pub fn subtree(&self, id: &Uuid) -> ShapesPoolImpl {
        let Some(shape) = self.get(id) else {
            panic!("Subtree not found");
        };

        let mut shapes = vec![];
        let mut new_idx = 0;
        let mut uuid_to_idx = HashMap::default();

        for child_id in shape.all_children_iter(self, true, true) {
            let Some(child_shape) = self.get(&child_id) else {
                panic!("Not found");
            };
            shapes.push(child_shape.clone());
            uuid_to_idx.insert(child_id, new_idx);
            new_idx += 1;
        }

        let shape_versions = vec![0; shapes.len()];
        ShapesPoolImpl {
            shapes,
            counter: new_idx,
            uuid_to_idx,
            modified_shape_cache: HashMap::default(),
            modifiers: HashMap::default(),
            structure: HashMap::default(),
            scale_content: HashMap::default(),
            shape_versions,
        }
    }

    fn to_update_bool(&self, shape: &Shape) -> bool {
        if !shape.is_bool() {
            return false;
        }

        let default = &Matrix::default();

        // Get parent modifier by index
        let parent_idx = self.uuid_to_idx.get(&shape.id);
        let parent_modifier = parent_idx
            .and_then(|idx| self.modifiers.get(idx))
            .unwrap_or(default);

        // Returns true if the transform of any child is different to the parent's
        shape.all_children_iter(self, true, false).any(|child_id| {
            let child_modifier = self
                .uuid_to_idx
                .get(&child_id)
                .and_then(|idx| self.modifiers.get(idx))
                .unwrap_or(default);
            !math::is_close_matrix(parent_modifier, child_modifier)
        })
    }
}
