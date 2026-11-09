package vangogh.app;

import java.lang.foreign.Arena;
import java.lang.foreign.FunctionDescriptor;
import java.lang.foreign.Linker;
import java.lang.foreign.MemorySegment;
import java.lang.foreign.SymbolLookup;
import java.lang.foreign.ValueLayout;
import java.lang.invoke.MethodHandle;

import build.krema.core.plugin.KremaPlugin;
import build.krema.core.plugin.PluginContext;

/**
 * Installs the macOS application menu bar for this Krema app
 * (App / Edit / View / Window, wired to real native actions).
 *
 * <p>Why this exists: the Krema 0.3.2 stack (webview C library → NSWindow/NSApp)
 * never creates an {@code NSMenu}, so the app runs with a nil application menu.
 * In normal windowed mode that's almost invisible — but native fullscreen
 * (green button) relies on the top-edge reveal to show <em>the app's menu bar
 * plus the window's traffic lights</em>; with no menu bar installed the reveal
 * has nothing to draw, so hovering the top edge in fullscreen looks dead (the
 * window still exits with Esc because the fullscreen space itself is fine).
 * Installing a menu bar restores the standard chrome and also gives the app its
 * Quit/Hide/Edit affordances, which a menuless process never has.
 *
 * <p>Why not Krema's {@code menu:setApplicationMenu} IPC: in 0.3.2 that API
 * creates every item with a <em>null action selector</em>
 * ({@code initWithTitle:action:keyEquivalent:} receives NULL), so NSMenu's
 * responder-chain auto-enablement marks all items disabled and shortcuts like
 * ⌘Q do nothing. We install items with real action selectors
 * ({@code terminate:}, {@code toggleFullScreen:}, {@code hide:},
 * {@code performZoom:}, webview {@code cut:}/{@code copy:}/{@code paste:}, …);
 * AppKit routes each through the first responder chain, so the menu works with
 * no additional click plumbing or event callbacks.
 *
 * FFM-subtlety that bit during the first attempt: only {@code objc_getClass},
 * {@code sel_registerName} and {@code stringWithUTF8String:} accept a raw
 * {@code char*}; every {@code id}-typed argument (titles, key equivalents) must
 * be a real NSString via {@code stringWithUTF8String:}. Passing a raw char*
 * where an id is expected corrupts message dispatch immediately
 * ({@code objc_msgSend+0x1d SIGSEGV}, reproduced by an isolated probe).
 *
 * <p>Threading: Krema invokes {@link #initialize(PluginContext)} inside
 * {@code run()} on the process's first thread <em>before</em> the AppKit event
 * loop starts — the one app-controlled point where Cocoa calls are safe without
 * a dispatch hook (the same reason the window itself needs
 * {@code -XstartOnFirstThread}). Best-effort: install failures are logged, never
 * fatal — the window keeps working and fullscreen keeps Esc. macOS-only:
 * {@code initialize} is a no-op on other platforms.
 */
final class MacMenubarPlugin implements KremaPlugin {

    /** NSEvent modifier-flag constants (64-bit AppKit layout). */
    private static final long NS_CONTROL = 1L << 18;
    private static final long NS_OPTION = 1L << 19;
    private static final long NS_SHIFT = 1L << 20;
    private static final long NS_COMMAND = 1L << 21;

    /** Marker item that renders as a native menu divider (no action, no title). */
    private static final Item SEP = new Item(null, null, null, 0);

    private static final Linker LINKER = Linker.nativeLinker();

    private final Arena arena = Arena.ofAuto();

    private MethodHandle objcGetClass;
    private MethodHandle objcSelRegister;
    // objc_msgSend bound once per shape used below (ids and NSUInteger).
    private MethodHandle msgSendNoArg;    // (id, SEL) -> id
    private MethodHandle msgSendOneId;    // (id, SEL, id) -> id
    private MethodHandle msgSendOneLong;  // (id, SEL, long) -> id
    private MethodHandle msgSendThreeIds; // (id, SEL, id, id, id) -> id

    // ── Plugin interface ─────────────────────────────────────────────────────

    @Override
    public String getId() {
        return "vangogh.menubar";
    }

    @Override
    public String getName() {
        return "Van Gogh Menu Bar";
    }

    @Override
    public String getVersion() {
        return "1.0.0";
    }

    @Override
    public String getDescription() {
        return "Installs the macOS application menu bar so native fullscreen chrome reveals "
                + "on top-edge hover.";
    }

    @Override
    public void initialize(PluginContext context) {
        String os = System.getProperty("os.name", "").toLowerCase();
        if (!os.contains("mac")) {
            return;
        }
        try {
            install();
        } catch (Throwable t) {
            // Best-effort: a missing menu bar must never abort the app; the window
            // still works and fullscreen keeps its Esc escape hatch.
            System.err.println("[Van Gogh] Failed to install macOS menu bar: " + t);
        }
    }

    // ── Menu assembly ─────────────────────────────────────────────────────────

    /** One menu item; {@code action == null} is unused (see {@link #SEP}). */
    private record Item(String title, String action, String key, long mods) {}

    private void install() throws Throwable {
        bindObjcRuntime();

        MemorySegment app = sharedApp();
        MemorySegment mainMenu = allocMenu("");

        // 1. App menu — macOS renders the process name on the first top-level item.
        topLevel(mainMenu, "Van Gogh",
                new Item("About Van Gogh", "orderFrontStandardAboutPanel:", null, 0),
                SEP,
                new Item("Hide Van Gogh", "hide:", "h", NS_COMMAND),
                new Item("Hide Others", "hideOtherApplications:", "h", NS_COMMAND | NS_OPTION),
                SEP,
                new Item("Quit Van Gogh", "terminate:", "q", NS_COMMAND));

        // 2. Edit — webview text actions resolve along the first responder chain.
        topLevel(mainMenu, "Edit",
                new Item("Undo", "undo:", "z", NS_COMMAND),
                new Item("Redo", "redo:", "z", NS_COMMAND | NS_SHIFT),
                SEP,
                new Item("Cut", "cut:", "x", NS_COMMAND),
                new Item("Copy", "copy:", "c", NS_COMMAND),
                new Item("Paste", "paste:", "v", NS_COMMAND),
                new Item("Delete", "delete:", null, 0),
                new Item("Select All", "selectAll:", "a", NS_COMMAND));

        // 3. View — the working fullscreen toggle (⌃⌘F), both in and out.
        topLevel(mainMenu, "View",
                new Item("Toggle Full Screen", "toggleFullScreen:", "f", NS_COMMAND | NS_CONTROL));

        // 4. Window — standard window management.
        topLevel(mainMenu, "Window",
                new Item("Minimize", "miniaturize:", "m", NS_COMMAND),
                new Item("Zoom", "performZoom:", null, 0),
                SEP,
                new Item("Bring All to Front", "arrangeInFront:", null, 0));

        // The install itself: [NSApp setMainMenu:mainMenu] — NSApp retains it.
        var _ = (MemorySegment) msgSendOneId.invokeExact(app, sel("setMainMenu:"), mainMenu);
    }

    private void topLevel(MemorySegment mainMenu, String title, Item... items) throws Throwable {
        // Top-level owner items are deliberately action-less: AppKit replaces the
        // first entry's title with the process name and shows the submenu directly.
        MemorySegment owner = newMenuItem(title, null, "");
        MemorySegment submenu = allocMenu(title);
        for (Item spec : items) {
            MemorySegment entry = (spec == SEP)
                    ? separatorItem()
                    : itemWithMask(spec);
            sendId(submenu, "addItem:", entry);
        }
        sendId(owner, "setSubmenu:", submenu);
        sendId(mainMenu, "addItem:", owner);
    }

    /** [[NSMenuItem alloc] initWithTitle:action:keyEquivalent:] + modifier mask. */
    private MemorySegment itemWithMask(Item spec) throws Throwable {
        MemorySegment item = newMenuItem(spec.title(), spec.action(), spec.key());
        var _ = (MemorySegment) msgSendOneLong.invokeExact(item, sel("setKeyEquivalentModifierMask:"),
                spec.mods() != 0 ? spec.mods() : NS_COMMAND);
        return item;
    }

    private MemorySegment newMenuItem(String title, String action, String key) throws Throwable {
        // Every argument here is an object id: NSString title, SEL action, NSString
        // key equivalent. Raw char* passed as an id faults in objc_msgSend.
        MemorySegment allocated = sendNoArg("NSMenuItem", "alloc");
        return (MemorySegment) msgSendThreeIds.invokeExact(
                allocated, sel("initWithTitle:action:keyEquivalent:"),
                str(title), sel(action), str(key == null ? "" : key));
    }

    /** Class factory: [NSMenuItem separatorItem]. */
    private MemorySegment separatorItem() throws Throwable {
        return sendNoArg("NSMenuItem", "separatorItem");
    }

    /** [[NSMenu alloc] initWithTitle:NSString] */
    private MemorySegment allocMenu(String title) throws Throwable {
        return (MemorySegment) msgSendOneId.invokeExact(
                sendNoArg("NSMenu", "alloc"), sel("initWithTitle:"), str(title));
    }

    private MemorySegment sharedApp() throws Throwable {
        return sendNoArg("NSApplication", "sharedApplication");
    }

    /**
     * Builds a real {@code NSString} for objc id-typed arguments.
     * {@code stringWithUTF8String:} is the ONE call that legitimately receives a
     * raw char* — objc_msgSend treats every other id argument as an object
     * (deref of a raw char* corrupted dispatch). Menu strings go through it.
     */
    private MemorySegment str(String value) throws Throwable {
        if (value == null) {
            return MemorySegment.NULL;
        }
        return (MemorySegment) msgSendOneId.invokeExact(cls("NSString"),
                sel("stringWithUTF8String:"), utf8(value));
    }

    // ── Downcall helpers ─────────────────────────────────────────────────────

    /** (id, SEL) -> id — class methods and property getters. */
    private MemorySegment sendNoArg(String className, String selector) throws Throwable {
        return (MemorySegment) msgSendNoArg.invokeExact(cls(className), sel(selector));
    }

    /** (id, SEL, id) -> id — one pointer argument (addItem:, setSubmenu:, …). */
    private void sendId(MemorySegment receiver, String selector, MemorySegment arg) throws Throwable {
        var _ = (MemorySegment) msgSendOneId.invokeExact(receiver, sel(selector), arg);
    }

    private MemorySegment cls(String name) throws Throwable {
        return (MemorySegment) objcGetClass.invokeExact(utf8(name));
    }

    private MemorySegment sel(String name) throws Throwable {
        return name == null ? MemorySegment.NULL
                : (MemorySegment) objcSelRegister.invokeExact(utf8(name));
    }

    /** NUL-terminated UTF-8 allocation for a native string. */
    private MemorySegment utf8(String value) {
        return arena.allocateFrom(value);
    }

    // ── Runtime bindings (the downcall shapes Krema 0.3.2 itself uses) ────────

    private void bindObjcRuntime() throws Throwable {
        SymbolLookup objc = SymbolLookup.libraryLookup("/usr/lib/libobjc.dylib", arena);
        // AppKit classes are live by plugin-init time (the webview engine booted
        // NSWindow already); loading the framework explicitly keeps it visible.
        SymbolLookup.libraryLookup("/System/Library/Frameworks/AppKit.framework/AppKit", arena);

        objcGetClass = LINKER.downcallHandle(
                objc.find("objc_getClass").orElseThrow(),
                FunctionDescriptor.of(ValueLayout.ADDRESS, ValueLayout.ADDRESS));
        objcSelRegister = LINKER.downcallHandle(
                objc.find("sel_registerName").orElseThrow(),
                FunctionDescriptor.of(ValueLayout.ADDRESS, ValueLayout.ADDRESS));

        MemorySegment send = objc.find("objc_msgSend").orElseThrow();
        msgSendNoArg = LINKER.downcallHandle(send, FunctionDescriptor.of(
                ValueLayout.ADDRESS, ValueLayout.ADDRESS, ValueLayout.ADDRESS));
        msgSendOneId = LINKER.downcallHandle(send, FunctionDescriptor.of(
                ValueLayout.ADDRESS, ValueLayout.ADDRESS, ValueLayout.ADDRESS, ValueLayout.ADDRESS));
        msgSendOneLong = LINKER.downcallHandle(send, FunctionDescriptor.of(
                ValueLayout.ADDRESS, ValueLayout.ADDRESS, ValueLayout.ADDRESS, ValueLayout.JAVA_LONG));
        msgSendThreeIds = LINKER.downcallHandle(send, FunctionDescriptor.of(
                ValueLayout.ADDRESS, ValueLayout.ADDRESS, ValueLayout.ADDRESS, ValueLayout.ADDRESS,
                ValueLayout.ADDRESS, ValueLayout.ADDRESS));
    }
}
