const std = @import("std");

// Yeow QuickJS bridge build.
//
// One toolchain for every platform: QuickJS (C) and the JNI bridge (C) are
// compiled by Zig and linked into a single shared library, then packaged with
// the Java API into one JAR.
//
//   zig build                 # native library for the host platform
//   zig build all             # native libraries for every platform -> zig-out/native/
//   zig build jar             # Java + every platform native -> zig-out/yeow-quickjs.jar
//
// Cross targets are selected with -Dtarget=<arch>-<os>-<abi>, e.g.
//   zig build -Dtarget=aarch64-linux-gnu

const quickjs_version = "2026-06-04";

const quickjs_sources = [_][]const u8{
    "native/quickjs/quickjs.c",
    "native/quickjs/cutils.c",
    "native/quickjs/dtoa.c",
    "native/quickjs/libregexp.c",
    "native/quickjs/libunicode.c",
};

const bridge_sources = [_][]const u8{
    "native/src/bridge.c",
    "native/src/context.c",
    "native/src/convert.c",
};

const polyfill_sources = [_][]const u8{
    "native/polyfill/polyfill.c",
    "native/polyfill/performance.c",
    "native/polyfill/text_codec.c",
};

const java_sources = [_][]const u8{
    "java/src/main/java/wiki/yexin/quickjs/QuickJSContext.java",
    "java/src/main/java/wiki/yexin/quickjs/QuickJSException.java",
    "java/src/main/java/wiki/yexin/quickjs/QuickJSNativeLoader.java",
};

const Platform = struct {
    name: []const u8,
    arch: std.Target.Cpu.Arch,
    os: std.Target.Os.Tag,
    abi: std.Target.Abi = .none,
    lib: []const u8,
};

const platforms = [_]Platform{
    .{ .name = "linux-x86_64", .arch = .x86_64, .os = .linux, .abi = .gnu, .lib = "libyeow-quickjs.so" },
    .{ .name = "linux-arm64", .arch = .aarch64, .os = .linux, .abi = .gnu, .lib = "libyeow-quickjs.so" },
    .{ .name = "macos-x86_64", .arch = .x86_64, .os = .macos, .lib = "libyeow-quickjs.dylib" },
    .{ .name = "macos-arm64", .arch = .aarch64, .os = .macos, .lib = "libyeow-quickjs.dylib" },
    .{ .name = "windows-x86_64", .arch = .x86_64, .os = .windows, .abi = .gnu, .lib = "yeow-quickjs.dll" },
    .{ .name = "windows-arm64", .arch = .aarch64, .os = .windows, .abi = .gnu, .lib = "yeow-quickjs.dll" },
};

fn jniPlatformDir(os: std.Target.Os.Tag) []const u8 {
    return switch (os) {
        .windows => "native/jni/win32",
        .macos => "native/jni/darwin",
        else => "native/jni/linux",
    };
}

fn addNativeLib(b: *std.Build, target: std.Build.ResolvedTarget, optimize: std.builtin.OptimizeMode) *std.Build.Step.Compile {
    const mod = b.createModule(.{
        .target = target,
        .optimize = optimize,
        .link_libc = true,
        .link_libcpp = false,
        .sanitize_c = .off,
        .strip = true,
    });

    mod.addIncludePath(b.path("native/jni"));
    mod.addIncludePath(b.path(jniPlatformDir(target.result.os.tag)));
    mod.addIncludePath(b.path("native/quickjs"));
    mod.addIncludePath(b.path("native/src"));
    mod.addIncludePath(b.path("native/polyfill"));

    mod.addCSourceFiles(.{
        .files = &quickjs_sources,
        .flags = &.{
            "-DCONFIG_VERSION=\"" ++ quickjs_version ++ "\"",
            "-fvisibility=hidden",
            "-Wno-unused-parameter",
        },
    });
    mod.addCSourceFiles(.{
        .files = &bridge_sources,
        .flags = &.{ "-fvisibility=hidden", "-Wno-unused-parameter" },
    });
    mod.addCSourceFiles(.{
        .files = &polyfill_sources,
        .flags = &.{ "-fvisibility=hidden", "-Wno-unused-parameter" },
    });

    if (target.result.os.tag != .windows) {
        mod.linkSystemLibrary("m", .{});
    }

    return b.addLibrary(.{
        .name = "yeow-quickjs",
        .root_module = mod,
        .linkage = .dynamic,
    });
}

pub fn build(b: *std.Build) void {
    const target = b.standardTargetOptions(.{});
    const optimize = b.standardOptimizeOption(.{ .preferred_optimize_mode = .ReleaseSmall });

    const host_lib = addNativeLib(b, target, optimize);
    b.installArtifact(host_lib);

    // `zig build all` — every platform into zig-out/native/<platform>/
    const all_step = b.step("all", "Build native libraries for every platform");

    // `zig build jar` — Java classes + all platform natives into one JAR.
    const jar_step = b.step("jar", "Build the yeow-quickjs JAR (Java + all platform natives)");

    // One staging tree holds both the compiled classes and the natives so the
    // JAR can be assembled with a single `jar -C <staging> .` (the JDK `jar`
    // tool only honours one -C).
    const staging = b.getInstallPath(.{ .custom = "staging" }, "");

    const javac = b.addSystemCommand(&.{ "javac", "-encoding", "UTF-8", "-d", staging });
    javac.has_side_effects = true;
    for (java_sources) |src| javac.addFileArg(b.path(src));

    const jar_cmd = b.addSystemCommand(&.{ "jar", "--create", "--file" });
    jar_cmd.has_side_effects = true;
    const jar_file = jar_cmd.addOutputFileArg("yeow-quickjs.jar");
    jar_cmd.addArg("-C");
    jar_cmd.addArg(staging);
    jar_cmd.addArg(".");
    jar_cmd.step.dependOn(&javac.step);

    for (platforms) |p| {
        const resolved = b.resolveTargetQuery(.{
            .cpu_arch = p.arch,
            .os_tag = p.os,
            .abi = p.abi,
        });
        const lib = addNativeLib(b, resolved, optimize);
        const rel = b.fmt("native/{s}/{s}", .{ p.name, p.lib });

        const install_all = b.addInstallFileWithDir(lib.getEmittedBin(), .prefix, rel);
        all_step.dependOn(&install_all.step);

        const install_res = b.addInstallFileWithDir(lib.getEmittedBin(), .{ .custom = "staging" }, rel);
        javac.step.dependOn(&install_res.step);
    }

    const install_jar = b.addInstallFileWithDir(jar_file, .prefix, "yeow-quickjs.jar");
    jar_step.dependOn(&install_jar.step);
}
