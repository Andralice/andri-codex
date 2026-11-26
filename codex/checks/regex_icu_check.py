#!/usr/bin/env python3
"""Compile the preview renderer's actual regexes with ICU, as Android does.

Run :app:exportCheckClasspath first. Requires a JDK and the host ICU libraries.
Java's Pattern accepts some syntax that Android rejects during class initialization.
"""
import base64
import ctypes
import ctypes.util
from pathlib import Path
import re
import subprocess


def main():
    project = Path(__file__).resolve().parents[2]
    output = project / "codex" / "regex-icu-runtime"
    output.mkdir(exist_ok=True)
    exporter = output / "ExportRegex.java"
    exporter.write_text('''import java.lang.reflect.*;
import java.nio.charset.StandardCharsets;
import java.util.Base64;
import kotlin.text.Regex;
class ExportRegex {
  public static void main(String[] args) throws Exception {
    for (String name : args) {
      for (Field field : Class.forName(name).getDeclaredFields()) {
        if (!Modifier.isStatic(field.getModifiers()) || field.getType() != Regex.class) continue;
        field.setAccessible(true);
        Regex regex = (Regex)field.get(null);
        System.out.println(name + "." + field.getName() + "\\t" +
            regex.toPattern().flags() + "\\t" + Base64.getEncoder().encodeToString(
            regex.getPattern().getBytes(StandardCharsets.UTF_8)));
      }
    }
  }
}
''', encoding="utf-8")
    classpath = (project / "codex" / "check-classpath.txt").read_text().strip()
    result = subprocess.run([
        "java", "-cp", classpath, str(exporter),
        "com.codexserver.mobile.VisualizationPreviewKt",
        "com.codexserver.mobile.ChatImageLinksKt",
        "com.codexserver.mobile.WorkspaceSourceLocationKt",
        "com.codexserver.mobile.WorkspaceMediaKt",
        "com.codexserver.mobile.WorkspaceSyntaxHighlightingKt",
    ], cwd=project, text=True, capture_output=True, check=True)
    library_name = ctypes.util.find_library("icui18n")
    if not library_name:
        raise RuntimeError("ICU is required to check Android regex compatibility")
    icu = ctypes.CDLL(library_name)
    version = re.search(r"\.(\d+)$", library_name)
    suffix = "_" + version[1] if version else ""
    def function(name, restype, *argtypes):
        value = getattr(icu, name + suffix)
        value.restype, value.argtypes = restype, argtypes
        return value
    open_regex = function("uregex_open", ctypes.c_void_p, ctypes.POINTER(ctypes.c_uint16),
                          ctypes.c_int32, ctypes.c_uint32, ctypes.c_void_p, ctypes.POINTER(ctypes.c_int32))
    close_regex = function("uregex_close", None, ctypes.c_void_p)
    error_name = function("u_errorName", ctypes.c_char_p, ctypes.c_int32)
    def compile_regex(pattern, flags=0):
        data = pattern.encode("utf-16-le")
        buffer = (ctypes.c_uint16 * (len(data) // 2)).from_buffer_copy(data)
        status = ctypes.c_int32(0)
        handle = open_regex(buffer, len(buffer), flags, None, ctypes.byref(status))
        if handle:
            close_regex(handle)
        return status.value
    # Prove that this gate detects the Android-only 0.1.62 startup crash.
    assert compile_regex(r"\{.*?}") > 0, "ICU must reject the previously shipped syntax"
    failures = []
    patterns = result.stdout.splitlines()
    assert len(patterns) >= 4, "No compiled renderer regexes were exported"
    for row in patterns:
        name, flags, encoded = row.split("\t")
        pattern = base64.b64decode(encoded).decode("utf-8")
        java_flags = int(flags)
        # java.util.regex.Pattern and ICU share these values for i, m and s.
        icu_flags = java_flags & (2 | 8 | 32)
        status = compile_regex(pattern, icu_flags)
        outcome = error_name(status).decode()
        print(f"{name}: {outcome}")
        if status > 0:
            failures.append(name)
    if failures:
        raise SystemExit(f"Android ICU rejected {len(failures)} regex(es)")
    print(f"Android ICU compatibility passed for {len(patterns)} compiled regexes")


if __name__ == "__main__":
    main()
