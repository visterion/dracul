package de.visterion.dracul.architecture;

import static org.assertj.core.api.Assertions.assertThat;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.file.FileSystems;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.PathMatcher;
import java.util.ArrayList;
import java.util.List;
import org.junit.jupiter.api.Test;

/**
 * W3-analog doctrine guard (T1.6, spec §9/§12): no deterministic gate/screen/detector may import
 * de.visterion.dracul.hivemem.{HiveMemClient,HiveMemResearchService}. Deliberately
 * dependency-free (no ArchUnit — not a pom.xml dependency and this plan does not add one): a
 * plain source-tree walk + substring check gives the same guarantee.
 *
 * <p>The daywalker/ package as a WHOLE is intentionally not banned — the /events prior_memory
 * pre-fetch (Task 11) legitimately lives in DaywalkerWebhookController, which sits directly
 * under daywalker/. Only the detector classes under daywalker/detect/ and DaywalkerEventEngine
 * itself are banned.
 */
class MemoryIsolationTest {

  private static final Path ROOT = Path.of("src/main/java/de/visterion/dracul");

  /**
   * Whole package roots that must never import HiveMem — everything under here is deterministic
   * (executor decision path; pattern gates).
   */
  private static final List<String> BANNED_ROOTS = List.of("executor", "pattern");

  /**
   * Specific files/subpackages banned even though their parent package is NOT wholesale banned
   * (e.g. daywalker/ hosts both the legitimate pre-fetch controller AND the banned detectors).
   * Glob syntax per java.nio.file.FileSystem#getPathMatcher("glob:..."). Screener/Screen classes
   * both match "*Screen*.java" (Screener contains "Screen").
   */
  private static final List<String> BANNED_GLOBS =
      List.of(
          "glob:strigoi/**/*Gate*.java",
          "glob:strigoi/*Gate*.java",
          "glob:strigoi/**/*Screen*.java",
          "glob:strigoi/*Screen*.java",
          // "**" requires at least one intervening path segment, so it does NOT match flat
          // files directly under detect/ (e.g. detect/NewsDetector.java) — only detect/*.java
          // catches those; both forms are kept so nested future files stay covered too.
          "glob:daywalker/detect/**",
          "glob:daywalker/detect/*.java",
          "glob:daywalker/DaywalkerEventEngine.java",
          "glob:voievod/*Detector*.java",
          "glob:voievod/ConsensusDetector.java");

  private static final List<String> BANNED_IMPORTS =
      List.of(
          "de.visterion.dracul.hivemem.HiveMemClient",
          "de.visterion.dracul.hivemem.HiveMemResearchService");

  @Test
  void deterministicComponentsDoNotImportHiveMemory() throws IOException {
    List<String> offenders = new ArrayList<>();
    List<Path> scanned = new ArrayList<>();
    try (var paths = Files.walk(ROOT)) {
      paths
          .filter(p -> p.toString().endsWith(".java"))
          .peek(scanned::add)
          .filter(MemoryIsolationTest::isBanned)
          .forEach(
              p -> {
                String src = readString(p);
                for (String banned : BANNED_IMPORTS) {
                  if (src.contains(banned)) {
                    offenders.add(ROOT.relativize(p) + " imports " + banned);
                  }
                }
              });
    }
    // Sanity floor: a wrong ROOT or an empty source tree must never silently pass this guard.
    assertThat(scanned.size())
        .as("source-tree walk must have visited a non-trivial number of .java files")
        .isGreaterThan(50);
    assertThat(offenders)
        .as("deterministic components must not touch HiveMem memory")
        .isEmpty();
  }

  private static boolean isBanned(Path javaFile) {
    Path rel = ROOT.relativize(javaFile);
    String firstSegment = rel.getNameCount() > 0 ? rel.getName(0).toString() : "";
    if (BANNED_ROOTS.contains(firstSegment)) {
      return true;
    }
    for (String globPattern : BANNED_GLOBS) {
      PathMatcher matcher = FileSystems.getDefault().getPathMatcher(globPattern);
      if (matcher.matches(rel)) {
        return true;
      }
    }
    return false;
  }

  private static String readString(Path p) {
    try {
      return Files.readString(p);
    } catch (IOException e) {
      throw new UncheckedIOException(e);
    }
  }
}
