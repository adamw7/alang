"""The ALang toolchain, installed with pip.

A wheel for Linux (x64, arm64, glibc 2.35 or newer), macOS (arm64) or Windows (x64) carries the native
binary, which needs no Java. Any other platform gets the wheel with the jar, which runs on Java 21 or newer:
``ALANG_JAVA``, else the Java of ``pip install "alang-toolchain[java]"``, else ``JAVA_HOME``, else ``java``
on PATH.

The ``alang`` command is the toolchain itself (``alang check prog.al --json``). From Python::

    import alang
    alang.check("prog.al")                                  # the report of `alang check --json`
    alang.call("prog.al", "word_counts", {"text": "a b a"})  # {"ok": True, "result": [...], ...}
    alang.tools("prog.al")                                  # tool definitions for a model
    alang.agents_md()                                       # the guide to hand an agent
    alang.invoke("run", "prog.al", input="...")             # any command, as a CompletedProcess
"""

import json
import os
import shutil
import subprocess
import sys
from pathlib import Path

__all__ = ["AlangError", "agents_md", "call", "check", "command", "invoke", "tools", "__version__"]

DIST_NAME = "alang-toolchain"
_HERE = Path(__file__).resolve().parent
_EXE = _HERE / "bin" / ("alang.exe" if os.name == "nt" else "alang")
_JAR = _HERE / "lib" / "alang.jar"


def _version():
    try:
        from importlib.metadata import version

        return version(DIST_NAME)
    except Exception:  # run from the source tree, not installed
        return "0+unknown"


__version__ = _version()


class AlangError(RuntimeError):
    """The toolchain could not be started, or did not answer in JSON."""


def _find_java():
    """The java executable for the jar, or None."""
    exe = "java.exe" if os.name == "nt" else "java"
    given = os.environ.get("ALANG_JAVA")
    if given:
        return given
    try:
        import jdk4py  # pip install "alang-toolchain[java]"

        if Path(jdk4py.JAVA).is_file():
            return str(jdk4py.JAVA)
    except Exception:
        pass
    home = os.environ.get("JAVA_HOME")
    if home and (Path(home) / "bin" / exe).is_file():
        return str(Path(home) / "bin" / exe)
    return shutil.which("java")


def command():
    """The command line that starts the toolchain, and the environment to start it in.

    Returns ``(argv, env)``: ``argv`` is the native binary, or java with the jar; append the toolchain's
    arguments to it. Raises AlangError when the jar needs Java and none is found.
    """
    env = dict(os.environ)
    if _EXE.is_file():
        return [str(_EXE)], env
    if not _JAR.is_file():
        raise AlangError(f"this {DIST_NAME} install has neither {_EXE} nor {_JAR}; reinstall it")
    java = _find_java()
    if java is None:
        raise AlangError(
            "ALang needs Java 21 or newer on this platform, and none was found (ALANG_JAVA, JAVA_HOME, "
            f'java on PATH): install it, or run: pip install "{DIST_NAME}[java]"'
        )
    # As the launchers in the release archives do: the JVM announces JAVA_TOOL_OPTIONS and _JAVA_OPTIONS on
    # stderr each time it starts, so give the same options on the command line instead, where they apply
    # unannounced, in this order so that _JAVA_OPTIONS still wins. A value with quotes, which the JVM would
    # read as one option with spaces, is left as it is.
    opts = []
    for var in ("JAVA_TOOL_OPTIONS", "_JAVA_OPTIONS"):
        value = env.get(var, "")
        if '"' not in value and "'" not in value:
            opts += value.split()
            env.pop(var, None)
    if os.name != "nt":
        # Java decodes command-line arguments with the locale's charset; under a C locale every
        # non-ASCII byte would be replaced.
        env["LC_ALL"] = "C.UTF-8"
    return [java, *opts, "-jar", str(_JAR)], env


def invoke(*args, input=None, timeout=None):
    """Runs ``alang ARGS...`` and returns the CompletedProcess, with stdout and stderr as text (UTF-8).

    ``input`` is the text for its stdin (for ``run`` with a ``fn main(input: str[N])``, or ``call ... -``).
    A nonzero exit code is not an exception: 1 means diagnostics, a failed test or a trap; 2 a usage error.
    """
    argv, env = command()
    # Bytes both ways, so stdin reaches the program exactly as given: text mode would turn "\n" into
    # "\r\n" on Windows.
    if isinstance(input, str):
        input = input.encode("utf-8")
    proc = subprocess.run([*argv, *map(str, args)], input=input or b"", capture_output=True, env=env, timeout=timeout)
    proc.stdout = proc.stdout.decode("utf-8", errors="replace")
    proc.stderr = proc.stderr.decode("utf-8", errors="replace")
    return proc


def _json(proc):
    try:
        return json.loads(proc.stdout)
    except ValueError:
        raise AlangError(
            f"alang exited with {proc.returncode} without JSON on stdout: {proc.stderr.strip() or proc.stdout.strip()}"
        ) from None


def check(path, *, timeout=None):
    """``alang check PATH --json``: a dict with ``ok``, ``errors``, ``warnings``, ``diagnostics`` (each with
    ``code``, ``line``, ``column``, ``message``, ``hint``) and ``memory``."""
    return _json(invoke("check", path, "--json", timeout=timeout))


def tools(path, *, timeout=None):
    """``alang tools PATH``: the JSON tool definitions (name, description, input_schema, input_examples)."""
    return _json(invoke("tools", path, timeout=timeout))


def call(path, tool, arguments=None, *, hosts=None, replay=None, timeout=None):
    """``alang call PATH TOOL``: calls a ``tool fn`` with a dict of arguments and returns the reply, a dict
    with ``ok`` and either ``result`` and ``stdout``, or ``error`` (with its ``code``, such as ``R0004``).

    ``hosts`` is the path of a ``--hosts`` file, the commands that answer the program's ``host fn`` calls;
    ``replay`` the path of a report whose ``host_calls`` answer them again. A reply that made any such calls
    lists them in ``host_calls``."""
    payload = json.dumps({} if arguments is None else arguments, ensure_ascii=False)
    options = []
    if hosts is not None:
        options += ["--hosts", hosts]
    if replay is not None:
        options += ["--replay", replay]
    return _json(invoke("call", path, tool, "-", *options, input=payload, timeout=timeout))


def agents_md():
    """AGENTS.md: the guide for an agent writing ALang, as text."""
    return (_HERE / "AGENTS.md").read_text(encoding="utf-8")


def main():
    """The ``alang`` command: the toolchain, with this process's stdin, stdout, stderr and exit code."""
    try:
        argv, env = command()
    except AlangError as e:
        print(f"alang: {e}", file=sys.stderr)
        return 127
    argv += sys.argv[1:]
    if os.name != "nt":
        os.execvpe(argv[0], argv, env)  # replaces this process: signals and exit codes need no relaying
    try:
        return subprocess.call(argv, env=env)
    except KeyboardInterrupt:
        return 130
