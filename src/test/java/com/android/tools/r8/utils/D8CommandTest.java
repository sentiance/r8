// Copyright (c) 2017, the R8 project authors. Please see the AUTHORS file
// for details. All rights reserved. Use of this source code is governed by a
// BSD-style license that can be found in the LICENSE file.
package com.android.tools.r8.utils;

import static com.android.tools.r8.ToolHelper.EXAMPLES_BUILD_DIR;
import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;

import com.android.tools.r8.CompilationException;
import com.android.tools.r8.CompilationMode;
import com.android.tools.r8.D8Command;
import com.android.tools.r8.ToolHelper;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import org.junit.Rule;
import org.junit.Test;
import org.junit.rules.ExpectedException;
import org.junit.rules.TemporaryFolder;

public class D8CommandTest {

  @Rule
  public ExpectedException thrown = ExpectedException.none();

  @Rule
  public TemporaryFolder temp = ToolHelper.getTemporaryFolderForTest();

  @Test
  public void emptyCommand() throws Throwable {
    verifyEmptyCommand(D8Command.builder().build());
    verifyEmptyCommand(parse());
    verifyEmptyCommand(parse(""));
    verifyEmptyCommand(parse("", ""));
    verifyEmptyCommand(parse(" "));
    verifyEmptyCommand(parse(" ", " "));
    verifyEmptyCommand(parse("\t"));
    verifyEmptyCommand(parse("\t", "\t"));
  }

  private void verifyEmptyCommand(D8Command command) {
    assertEquals(0, ToolHelper.getApp(command).getDexProgramResources().size());
    assertEquals(0, ToolHelper.getApp(command).getClassProgramResources().size());
    assertEquals(0, ToolHelper.getApp(command).getDexLibraryResources().size());
    assertEquals(0, ToolHelper.getApp(command).getClassLibraryResources().size());
    assertFalse(ToolHelper.getApp(command).hasMainDexList());
    assertFalse(ToolHelper.getApp(command).hasProguardMap());
    assertFalse(ToolHelper.getApp(command).hasProguardSeeds());
    assertFalse(ToolHelper.getApp(command).hasPackageDistribution());
    assertNull(command.getOutputPath());
    assertEquals(CompilationMode.DEBUG, command.getMode());
  }

  @Test
  public void defaultOutIsCwd() throws IOException, InterruptedException {
    Path working = temp.getRoot().toPath();
    Path input = Paths.get(EXAMPLES_BUILD_DIR, "arithmetic.jar").toAbsolutePath();
    Path output = working.resolve("classes.dex");
    assertFalse(Files.exists(output));
    assertEquals(0, ToolHelper.forkD8(working, input.toString()).exitCode);
    assertTrue(Files.exists(output));
  }

  @Test
  public void validOutputPath() throws Throwable {
    Path existingDir = temp.getRoot().toPath();
    Path nonExistingZip = existingDir.resolve("a-non-existing-archive.zip");
    assertEquals(
        existingDir,
        D8Command.builder().setOutputPath(existingDir).build().getOutputPath());
    assertEquals(
        nonExistingZip,
        D8Command.builder().setOutputPath(nonExistingZip).build().getOutputPath());
    assertEquals(
        existingDir,
        parse("--output", existingDir.toString()).getOutputPath());
    assertEquals(
        nonExistingZip,
        parse("--output", nonExistingZip.toString()).getOutputPath());
  }

  @Test
  public void nonExistingOutputDir() throws Throwable {
    thrown.expect(CompilationException.class);
    Path nonExistingDir = temp.getRoot().toPath().resolve("a/path/that/does/not/exist");
    D8Command.builder().setOutputPath(nonExistingDir).build();
  }

  @Test
  public void existingOutputZip() throws Throwable {
    thrown.expect(CompilationException.class);
    Path existingZip = temp.newFile("an-existing-archive.zip").toPath();
    D8Command.builder().setOutputPath(existingZip).build();
  }

  @Test
  public void invalidOutputFileType() throws Throwable {
    thrown.expect(CompilationException.class);
    Path invalidType = temp.getRoot().toPath().resolve("an-invalid-output-file-type.foobar");
    D8Command.builder().setOutputPath(invalidType).build();
  }

  @Test
  public void nonExistingOutputDirParse() throws Throwable {
    thrown.expect(CompilationException.class);
    Path nonExistingDir = temp.getRoot().toPath().resolve("a/path/that/does/not/exist");
    parse("--output", nonExistingDir.toString());
  }

  @Test
  public void existingOutputZipParse() throws Throwable {
    thrown.expect(CompilationException.class);
    Path existingZip = temp.newFile("an-existing-archive.zip").toPath();
    parse("--output", existingZip.toString());
  }

  @Test
  public void invalidOutputFileTypeParse() throws Throwable {
    thrown.expect(CompilationException.class);
    Path invalidType = temp.getRoot().toPath().resolve("an-invalid-output-file-type.foobar");
    parse("--output", invalidType.toString());
  }

  private D8Command parse(String... args) throws IOException, CompilationException {
    return D8Command.parse(args).build();
  }
}
