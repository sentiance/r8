#!/usr/bin/env python3
# Copyright (c) 2020, the R8 project authors. Please see the AUTHORS file
# for details. All rights reserved. Use of this source code is governed by a
# BSD-style license that can be found in the LICENSE file.

import argparse
import os
import subprocess
import sys
import zipfile

import archive
import jdk
import retrace
import utils


def make_parser():
  parser = argparse.ArgumentParser(description = 'Compile a dump artifact.')
  parser.add_argument(
      '--summary',
      help='List a summary of the contents of the dumps.',
      default=False,
      action='store_true')
  parser.add_argument(
    '-d',
    '--dump',
    help='Dump file or directory to compile',
    default=None)
  parser.add_argument(
    '--temp',
    help='Temp directory to extract the dump to, allows you to rerun the command'
      ' more easily in the terminal with changes',
    default=None)
  parser.add_argument(
    '-c',
    '--compiler',
    help='Compiler to use',
    default=None)
  parser.add_argument(
    '--minify',
    help='Force enable/disable minification'
      ' (defaults to app proguard config)',
    choices=['default', 'force-enable', 'force-disable'],
    default='default')
  parser.add_argument(
    '--optimize',
    help='Force enable/disable optimizations'
      ' (defaults to app proguard config)',
    choices=['default', 'force-enable', 'force-disable'],
    default='default')
  parser.add_argument(
    '--shrink',
    help='Force enable/disable shrinking'
      ' (defaults to app proguard config)',
    choices=['default', 'force-enable', 'force-disable'],
    default='default')
  parser.add_argument(
    '-v',
    '--version',
    help='Compiler version to use (default read from dump version file).'
      'Valid arguments are:'
      '  "main" to run from your own tree,'
      '  "source" to run from build classes directly,'
      '  "X.Y.Z" to run a specific version, or'
      '  <hash> to run that hash from main.',
    default=None)
  parser.add_argument(
    '--r8-jar',
    help='Path to an R8 jar.',
    default=None)
  parser.add_argument(
    '--r8-flags', '--r8_flags',
    help='Additional option(s) for the compiler.')
  parser.add_argument(
    '--override',
    help='Do not override any extracted dump in temp-dir',
    default=False,
    action='store_true')
  parser.add_argument(
    '--nolib',
    help='Use the non-lib distribution (default uses the lib distribution)',
    default=False,
    action='store_true')
  parser.add_argument(
    '--print-times',
    help='Print timing information from r8',
    default=False,
    action='store_true')
  parser.add_argument(
    '--ea',
    help='Enable Java assertions when running the compiler (default disabled)',
    default=False,
    action='store_true')
  parser.add_argument(
    '--classfile',
    help='Run with classfile output',
    default=False,
    action='store_true')
  parser.add_argument(
      '--debug-agent',
      help='Enable Java debug agent and suspend compilation (default disabled)',
      default=False,
      action='store_true')
  parser.add_argument(
      '--xmx',
      help='Set JVM max heap size (-Xmx)',
      default=None)
  parser.add_argument(
      '--threads',
      help='Set the number of threads to use',
      default=None)
  parser.add_argument(
      '--min-api',
      help='Set min-api (default read from dump properties file)',
      default=None)
  parser.add_argument(
    '--desugared-lib',
    help='Set desugared-library (default set from dump)',
    default=None)
  parser.add_argument(
    '--disable-desugared-lib',
    help='Disable desugared-libary if it will be set from dump',
    default=False,
    action='store_true'
  )
  parser.add_argument(
    '--loop',
    help='Run the compilation in a loop',
    default=False,
    action='store_true')
  parser.add_argument(
    '--enable-missing-library-api-modeling',
    help='Run with api modeling',
    default=False,
    action='store_true')
  parser.add_argument(
    '--android-platform-build',
    help='Run as a platform build',
    default=False,
    action='store_true')
  return parser

def error(msg):
  print(msg)
  sys.exit(1)

class Dump(object):

  def __init__(self, directory):
    self.directory = directory

  def if_exists(self, name):
    f = os.path.join(self.directory, name)
    if os.path.exists(f):
      return f
    return None

  def program_jar(self):
    return self.if_exists('program.jar')

  def feature_jars(self):
    feature_jars = []
    i = 1
    while True:
      feature_jar = self.if_exists('feature-%s.jar' % i)
      if feature_jar:
        feature_jars.append(feature_jar)
        i = i + 1
      else:
        return feature_jars

  def library_jar(self):
    return self.if_exists('library.jar')

  def classpath_jar(self):
    return self.if_exists('classpath.jar')

  def desugared_library_json(self):
    return self.if_exists('desugared-library.json')

  def proguard_input_map(self):
    if self.if_exists('proguard_input.config'):
      print("Unimplemented: proguard_input configuration.")

  def main_dex_list_resource(self):
    if self.if_exists('main-dex-list.txt'):
      print("Unimplemented: main-dex-list.")

  def main_dex_rules_resource(self):
    return self.if_exists('main-dex-rules.txt')

  def build_properties_file(self):
    return self.if_exists('build.properties')

  def config_file(self):
    return self.if_exists('proguard.config')

  def version_file(self):
    return self.if_exists('r8-version')

  def version(self):
    f = self.version_file()
    if f:
      return open(f).read().split(' ')[0]
    return None

def read_dump_from_args(args, temp):
  if args.dump is None:
    error("A dump file or directory must be specified")
  return read_dump(args.dump, temp, args.override)

def read_dump(dump, temp, override=False):
  if os.path.isdir(dump):
    return Dump(dump)
  dump_file = zipfile.ZipFile(os.path.abspath(dump), 'r')
  with utils.ChangedWorkingDirectory(temp, quiet=True):
    if override or not os.path.isfile('r8-version'):
      dump_file.extractall()
      if not os.path.isfile('r8-version'):
        error("Did not extract into %s. Either the zip file is invalid or the "
              "dump is missing files" % temp)
    return Dump(temp)

def determine_build_properties(args, dump):
  build_properties = {}
  build_properties_file = dump.build_properties_file()
  if build_properties_file:
    with open(build_properties_file) as f:
      build_properties_contents = f.readlines()
      for line in build_properties_contents:
        stripped = line.strip()
        if stripped:
          pair = stripped.split('=')
          build_properties[pair[0]] = pair[1]
  return build_properties

def determine_version(args, dump):
  if args.version is None:
    return dump.version()
  return args.version

def determine_compiler(args, build_properties):
  compilers = ['d8', 'r8', 'r8full', 'l8']
  compiler = args.compiler
  if not compiler and 'tool' in build_properties:
    compiler = build_properties.get('tool').lower()
    if (compiler == 'r8'):
      if not 'force-proguard-compatibility' in build_properties:
        error("Unable to determine R8 compiler variant from build.properties."
              " No value for 'force-proguard-compatibility'.")
      if build_properties.get('force-proguard-compatibility').lower() == 'false':
        compiler = compiler + 'full'
  if compiler not in compilers:
    error("Unable to determine a compiler to use. Specified %s,"
          " Valid options: %s" % (args.compiler, ', '.join(compilers)))
  return compiler

def determine_output(args, temp):
  return os.path.join(temp, 'out.jar')

def determine_min_api(args, build_properties):
  if args.min_api:
    return args.min_api
  if 'min-api' in build_properties:
    return build_properties.get('min-api')
  return None

def determine_feature_output(feature_jar, temp):
  return os.path.join(temp, os.path.basename(feature_jar)[:-4] + ".out.jar")

def determine_program_jar(args, dump):
  if hasattr(args, 'program_jar') and args.program_jar:
    return args.program_jar
  return dump.program_jar()

def determine_class_file(args, build_properties):
  if args.classfile:
    return args.classfile
  if 'classfile' in build_properties:
    return True
  return None

def determine_android_platform_build(args, build_properties):
  if args.android_platform_build:
    return args.android_platform_build
  if 'android-platform-build=true' in build_properties:
    return True
  return None

def determine_enable_missing_library_api_modeling(args, build_properties):
  if args.enable_missing_library_api_modeling:
    return args.enable_missing_library_api_modeling
  if 'enable-missing-library-api-modeling=true' in build_properties:
    return True
  return None

def determine_properties(build_properties):
  args = []
  for key, value in build_properties.items():
    # When writing dumps all system properties starting with com.android.tools.r8
    # are written to the build.properties file in the format
    # system-property-com.android.tools.r8.XXX=<value>
    if key.startswith('system-property-'):
      name = key[len('system-property-'):]
      if name.endswith('dumpinputtofile') or name.endswith('dumpinputtodirectory'):
        continue
      if len(value) == 0:
        args.append('-D' + name)
      else:
        args.append('-D' + name + '=' + value)
  return args

def download_distribution(version, nolib, temp):
  if version == 'main':
    return utils.R8_JAR if nolib else utils.R8LIB_JAR
  if version == 'source':
    return '%s:%s' % (utils.BUILD_JAVA_MAIN_DIR, utils.ALL_DEPS_JAR)
  name = 'r8.jar' if nolib else 'r8lib.jar'
  source = archive.GetUploadDestination(version, name, is_hash(version))
  dest = os.path.join(temp, 'r8.jar')
  utils.download_file_from_cloud_storage(source, dest)
  return dest


def clean_config(file, args):
  with open(file) as f:
    lines = f.readlines()
  minify = args.minify
  optimize = args.optimize
  shrink = args.shrink
  with open(file, 'w') as f:
    if minify == 'force-disable':
      print('Adding config line: -dontobfuscate')
      f.write('-dontobfuscate\n')
    if optimize == 'force-disable':
      print('Adding config line: -dontoptimize')
      f.write('-dontoptimize\n')
    if shrink == 'force-disable':
      print('Adding config line: -dontshrink')
      f.write('-dontshrink\n')
    for line in lines:
      if clean_config_line(line, minify, optimize, shrink):
        print('Removing from config line: \n%s' % line)
      else:
        f.write(line)

def clean_config_line(line, minify, optimize, shrink):
  if ('-injars' in line or '-libraryjars' in line or
      '-print' in line):
    return True
  if minify == 'force-enable' and '-dontobfuscate' in line:
    return True
  if optimize == 'force-enable' and '-dontoptimize' in line:
    return True
  if shrink == 'force-enable' and '-dontshrink' in line:
    return True
  return False

def prepare_r8_wrapper(dist, temp, jdkhome):
  compile_with_javac(
    dist,
    temp,
    jdkhome,
    os.path.join(
      utils.REPO_ROOT,
      'src/main/java/com/android/tools/r8/utils/CompileDumpCompatR8.java'))

def prepare_d8_wrapper(dist, temp, jdkhome):
  compile_with_javac(
    dist,
    temp,
    jdkhome,
    os.path.join(
      utils.REPO_ROOT,
      'src/main/java/com/android/tools/r8/utils/CompileDumpD8.java'))

def compile_with_javac(dist, temp, jdkhome, path):
  cmd = [
    jdk.GetJavacExecutable(jdkhome),
    path,
    '-d', temp,
    '-cp', dist,
  ]
  utils.PrintCmd(cmd)
  subprocess.check_output(cmd)

def is_hash(version):
  return len(version) == 40

def run1(out, args, otherargs, jdkhome=None):
  with utils.TempDir() as temp:
    if out:
      temp = out
      if not os.path.exists(temp):
        os.makedirs(temp)
    dump = read_dump_from_args(args, temp)
    if not dump.program_jar():
      error("Cannot compile dump with no program classes")
    if not dump.library_jar():
      print("WARNING: Unexpected lack of library classes in dump")
    build_properties = determine_build_properties(args, dump)
    version = determine_version(args, dump)
    compiler = determine_compiler(args, build_properties)
    out = determine_output(args, temp)
    min_api = determine_min_api(args, build_properties)
    classfile = determine_class_file(args, build_properties)
    android_platform_build = determine_android_platform_build(args, build_properties)
    enable_missing_library_api_modeling = determine_enable_missing_library_api_modeling(args, build_properties)
    jar = args.r8_jar if args.r8_jar else download_distribution(version, args.nolib, temp)
    if ':' not in jar and not os.path.exists(jar):
      error("Distribution does not exist: " + jar)
    prepare_r8_wrapper(jar, temp, jdkhome)
    prepare_d8_wrapper(jar, temp, jdkhome)
    cmd = [jdk.GetJavaExecutable(jdkhome)]
    if args.debug_agent:
      if not args.nolib:
        print("WARNING: Running debugging agent on r8lib is questionable...")
      cmd.append(
          '-agentlib:jdwp=transport=dt_socket,server=y,suspend=y,address=*:5005')
    if args.xmx:
      cmd.append('-Xmx' + args.xmx)
    if args.ea:
      cmd.append('-ea')
      cmd.append('-Dcom.android.tools.r8.enableTestAssertions=1')
    if args.print_times:
      cmd.append('-Dcom.android.tools.r8.printtimes=1')
    if args.r8_flags:
      cmd.extend(args.r8_flags.split(' '))
    if hasattr(args, 'properties'):
      cmd.extend(args.properties);
    cmd.extend(determine_properties(build_properties))
    cmd.extend(['-cp', '%s:%s' % (temp, jar)])
    if compiler == 'd8':
      cmd.append('com.android.tools.r8.utils.CompileDumpD8')
    if compiler == 'l8':
      cmd.append('com.android.tools.r8.L8')
    if compiler.startswith('r8'):
      cmd.append('com.android.tools.r8.utils.CompileDumpCompatR8')
    if compiler == 'r8':
      cmd.append('--compat')
    # For recompilation of dumps run_on_app_dumps pass in a program jar.
    cmd.append(determine_program_jar(args, dump))
    cmd.extend(['--output', out])
    for feature_jar in dump.feature_jars():
      cmd.extend(['--feature-jar', feature_jar,
                 determine_feature_output(feature_jar, temp)])
    if dump.library_jar():
      cmd.extend(['--lib', dump.library_jar()])
    if dump.classpath_jar() and compiler != 'l8':
      cmd.extend(['--classpath', dump.classpath_jar()])
    if dump.desugared_library_json() and not args.disable_desugared_lib:
      cmd.extend(['--desugared-lib', dump.desugared_library_json()])
    if compiler != 'd8' and dump.config_file():
      if hasattr(args, 'config_file_consumer') and args.config_file_consumer:
        args.config_file_consumer(dump.config_file())
      else:
        # If we get a dump from the wild we can't use -injars, -libraryjars or
        # -print{mapping,usage}
        clean_config(dump.config_file(), args)
      cmd.extend(['--pg-conf', dump.config_file()])
    if dump.main_dex_rules_resource():
      cmd.extend(['--main-dex-rules', dump.main_dex_rules_resource()])
    if compiler == 'l8':
      if dump.config_file():
        cmd.extend(['--pg-map-output', '%s.map' % out])
    elif compiler != 'd8':
      cmd.extend(['--pg-map-output', '%s.map' % out])
    if min_api:
      cmd.extend(['--min-api', min_api])
    if classfile:
      cmd.extend(['--classfile'])
    if android_platform_build:
      cmd.extend(['--android-platform-build'])
    if enable_missing_library_api_modeling:
      cmd.extend(['--enable-missing-library-api-modeling'])
    if args.threads:
      cmd.extend(['--threads', args.threads])
    cmd.extend(otherargs)
    utils.PrintCmd(cmd)
    try:
      print(subprocess.check_output(cmd, stderr=subprocess.STDOUT).decode('utf-8'))
      return 0
    except subprocess.CalledProcessError as e:
      if args.nolib \
          or version == 'source' \
          or not try_retrace_output(e, version, temp):
        print(e.output.decode('UTF-8'))
      return 1

def try_retrace_output(e, version, temp):
  try:
    stacktrace = os.path.join(temp, 'stacktrace')
    open(stacktrace, 'w+').write(e.output.decode('UTF-8'))
    print("=" * 80)
    print(" RETRACED OUTPUT")
    print("=" * 80)
    retrace.run(get_map_file(version, temp), stacktrace, no_r8lib=False)
    return True
  except Exception as e2:
    print("Failed to retrace for version: %s" % version)
    print(e2)
    return False

def get_map_file(version, temp):
  if version == 'main':
    return utils.R8LIB_MAP
  download_path = archive.GetUploadDestination(
        version,
        'r8lib.jar.map',
        is_hash(version))
  if utils.file_exists_on_cloud_storage(download_path):
    map_path = os.path.join(temp, 'mapping.map')
    utils.download_file_from_cloud_storage(download_path, map_path)
    return map_path
  else:
    print('Could not find map file from argument: %s.' % version)
    return None

def summarize_dump_files(dumpfiles):
  if len(dumpfiles) == 0:
    error('Summary command expects a list of dumps to summarize')
  for f in dumpfiles:
    print(f + ':')
    try:
      with utils.TempDir() as temp:
        dump = read_dump(f, temp)
        summarize_dump(dump)
    except IOError as e:
      print("Error: " + str(e))
    except zipfile.BadZipfile as e:
      print("Error: " + str(e))

def summarize_dump(dump):
  version = dump.version()
  if not version:
    print('No dump version info')
    return
  print('version=' + version)
  props = dump.build_properties_file()
  if props:
    with open(props) as props_file:
      print(props_file.read())
  if dump.library_jar():
    print('library.jar present')
  if dump.classpath_jar():
    print('classpath.jar present')
  prog = dump.program_jar()
  if prog:
    print('program.jar content:')
    summarize_jar(prog)

def summarize_jar(jar):
  with zipfile.ZipFile(jar) as zip:
    pkgs = {}
    for info in zip.infolist():
      if info.filename.endswith('.class'):
        pkg, clazz = os.path.split(info.filename)
        count = pkgs.get(pkg, 0)
        pkgs[pkg] = count + 1
  sorted = list(pkgs.keys())
  sorted.sort()
  for p in sorted:
    print('  ' + p + ': ' + str(pkgs[p]))

def run(args, otherargs):
  if args.summary:
    summarize_dump_files(otherargs)
  elif args.loop:
    count = 1
    while True:
      print('Iteration {:03d}'.format(count))
      out = args.temp
      if out:
        out = os.path.join(out, '{:03d}'.format(count))
      run1(out, args, otherargs)
      count += 1
  else:
    run1(args.temp, args, otherargs)

if __name__ == '__main__':
  (args, otherargs) = make_parser().parse_known_args(sys.argv[1:])
  sys.exit(run(args, otherargs))
