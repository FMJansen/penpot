// Render options flags
const DEBUG_VISIBLE: u32 = 0x01;
const PROFILE_REBUILD_TILES: u32 = 0x02;
const TEXT_EDITOR_V3: u32 = 0x04;
const SHOW_WASM_INFO: u32 = 0x08;

// Render performance options
// This is the extra area used for tile rendering (tiles beyond viewport).
// Higher values pre-render more tiles, reducing empty squares during pan but using more memory.
const VIEWPORT_INTEREST_AREA_THRESHOLD: i32 = 3;
const MAX_BLOCKING_TIME_MS: i32 = 32;
const NODE_BATCH_THRESHOLD: i32 = 3;
const BLUR_DOWNSCALE_THRESHOLD: f32 = 8.0;
const ANTIALIAS_THRESHOLD: f32 = 7.0;
#[derive(Debug, Copy, Clone, PartialEq)]
pub struct RenderOptions {
    pub flags: u32,
    pub dpr: Option<f32>,
    fast_mode: bool,
    /// Active while the user is interacting with a shape (drag, resize,
    /// rotate). Implies `fast_mode` semantics for expensive effects but
    /// keeps per-frame flushing enabled (unlike pan/zoom, where
    /// `render_from_cache` drives target presentation).
    interactive_transform: bool,
    /// Opt-in switch for the retained-mode rendering path: when
    /// enabled, top-level shapes are rasterized once to a `ShapeCache`
    /// and recomposed every frame applying their modifier matrix as a
    /// canvas transform, instead of being re-rasterized from scratch.
    /// Behaves like the SVG compositor in the browser.
    retained_mode: bool,
    /// Per-leaf texture cache. When enabled the tile walker blits the
    /// cached image of a leaf shape (any shape where `!is_recursive()`)
    /// instead of re-rasterizing it, as long as the cached entry is
    /// fresh (same `shape_version`, same effective capture scale).
    /// Leaves that are visible but not yet cached are captured in a
    /// post-walker pass, with a per-frame cap, so the first frame is
    /// paid for once and subsequent frames become GPU blits. Unlike
    /// `retained_mode` this keeps the tile + atlas pipeline as the
    /// main driver, so pan/zoom caching behaviour is unchanged.
    leaf_cache: bool,
    /// Minimum on-screen size (CSS px at 1:1 zoom) above which vector antialiasing is enabled.
    pub antialias_threshold: f32,
    pub viewport_interest_area_threshold: i32,
    pub max_blocking_time_ms: i32,
    pub node_batch_threshold: i32,
    pub blur_downscale_threshold: f32,
}

impl Default for RenderOptions {
    fn default() -> Self {
        Self {
            flags: 0,
            dpr: None,
            fast_mode: false,
            interactive_transform: false,
            antialias_threshold: ANTIALIAS_THRESHOLD,
            viewport_interest_area_threshold: VIEWPORT_INTEREST_AREA_THRESHOLD,
            max_blocking_time_ms: MAX_BLOCKING_TIME_MS,
            node_batch_threshold: NODE_BATCH_THRESHOLD,
            blur_downscale_threshold: BLUR_DOWNSCALE_THRESHOLD,
            // Retained-mode (Figma-style per-top-level-shape texture
            // cache) is OFF by default: the tile + atlas pipeline
            // owns the render loop and we accelerate edits through
            // `leaf_cache` instead, which blits cached textures for
            // leaf shapes from inside the tile walker. Retained mode
            // is still available for A/B comparisons via
            // `set_retained_mode(true)`.
            retained_mode: false,
            // Per-leaf texture cache is ON by default: while the tile
            // walker traverses the scene it blits the cached texture
            // of any leaf shape instead of re-rasterizing it,
            // dramatically cutting the cost of repeated frames during
            // edits.
            leaf_cache: true,
        }
    }
}

impl RenderOptions {
    pub fn is_debug_visible(&self) -> bool {
        self.flags & DEBUG_VISIBLE == DEBUG_VISIBLE
    }

    pub fn is_profile_rebuild_tiles(&self) -> bool {
        self.flags & PROFILE_REBUILD_TILES == PROFILE_REBUILD_TILES
    }

    /// Use fast mode to enable / disable expensive operations
    pub fn is_fast_mode(&self) -> bool {
        self.fast_mode
    }

    pub fn set_fast_mode(&mut self, enabled: bool) {
        self.fast_mode = enabled;
    }

    /// Interactive transform is ON while the user is dragging, resizing
    /// or rotating a shape. Callers use it to keep per-frame flushing
    /// enabled and to render visible tiles in a single frame so tiles
    /// never appear sequentially or flicker during the gesture.
    pub fn is_interactive_transform(&self) -> bool {
        self.interactive_transform
    }

    pub fn set_interactive_transform(&mut self, enabled: bool) {
        self.interactive_transform = enabled;
    }

    /// Returns `true` when the retained-mode compositor should own the
    /// render loop. Mirrors the Figma-style "one texture per top-level
    /// shape" approach.
    pub fn is_retained_mode(&self) -> bool {
        self.retained_mode
    }

    pub fn set_retained_mode(&mut self, enabled: bool) {
        self.retained_mode = enabled;
    }

    /// Returns `true` when the per-leaf texture cache should be
    /// consulted by the tile walker.
    pub fn is_leaf_cache(&self) -> bool {
        self.leaf_cache
    }

    pub fn set_leaf_cache(&mut self, enabled: bool) {
        self.leaf_cache = enabled;
    }

    /// True only when the viewport is the one being moved (pan/zoom)
    /// and the dedicated `render_from_cache` path owns Target
    /// presentation. In this mode `process_animation_frame` must not
    /// flush to avoid presenting stale tile positions.
    pub fn is_viewport_interaction(&self) -> bool {
        self.fast_mode && !self.interactive_transform
    }

    pub fn dpr(&self) -> f32 {
        self.dpr.unwrap_or(1.0)
    }

    pub fn is_text_editor_v3(&self) -> bool {
        self.flags & TEXT_EDITOR_V3 == TEXT_EDITOR_V3
    }

    pub fn show_wasm_info(&self) -> bool {
        self.flags & SHOW_WASM_INFO == SHOW_WASM_INFO
    }

    pub fn set_antialias_threshold(&mut self, value: f32) {
        if value.is_finite() && value > 0.0 {
            self.antialias_threshold = value;
        }
    }

    pub fn set_blur_downscale_threshold(&mut self, value: f32) {
        if value.is_finite() && value > 0.0 {
            self.blur_downscale_threshold = value;
        }
    }

    pub fn set_viewport_interest_area_threshold(&mut self, value: i32) {
        if value >= 0 {
            self.viewport_interest_area_threshold = value;
        }
    }

    pub fn set_node_batch_threshold(&mut self, value: i32) {
        if value > 0 {
            self.node_batch_threshold = value;
        }
    }

    pub fn set_max_blocking_time_ms(&mut self, value: i32) {
        if value > 0 {
            self.max_blocking_time_ms = value;
        }
    }
}
