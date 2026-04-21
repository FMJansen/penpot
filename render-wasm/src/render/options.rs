// Render options flags
const DEBUG_VISIBLE: u32 = 0x01;
const PROFILE_REBUILD_TILES: u32 = 0x02;
const TEXT_EDITOR_V3: u32 = 0x04;
const SHOW_WASM_INFO: u32 = 0x08;

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
    /// Minimum on-screen size (CSS px at 1:1 zoom) above which vector antialiasing is enabled.
    pub antialias_threshold: f32,
}

impl Default for RenderOptions {
    fn default() -> Self {
        Self {
            flags: 0,
            dpr: None,
            fast_mode: false,
            interactive_transform: false,
            // Retained-mode (Figma-style per-top-level-shape texture
            // cache) is ON by default: drag/resize/rotate become
            // pure canvas transforms over cached textures and
            // pan/zoom reuses those same textures instead of going
            // through the tile atlas pipeline. Set to `false` or
            // call `set_retained_mode_enabled(false)` to fall back to
            // the legacy tile pipeline for A/B comparisons.
            retained_mode: true,
            antialias_threshold: 7.0,
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
}
