"""Run the same SMS workload with default-off Owner observations enabled on Server only."""
from pathlib import Path
import os
import shutil
import subprocess
import run_acceptance

load_preview = run_acceptance.load_preview


def observed_preview(root):
    module = load_preview(root)

    class ObservedPreview(module.Preview):
        def launch(self, name, command, env):
            if name == "server":
                self.jcmd = Path(shutil.which(command[0]) or command[0]).with_name("jcmd.exe" if os.name == "nt" else "jcmd")
                settings = Path(__file__).with_name("refill-observation.jfc").resolve()
                recording = self.output / "refill.jfr"
                command = [command[0], f"-XX:StartFlightRecording=name=refill,settings={settings},"
                           f"filename={recording},dumponexit=true,maxsize=128m", *command[1:]]
            return super().launch(name, command, env)

        def close(self):
            failure = None
            server = self.processes.get("server")
            if not self.closed and server is not None and server.poll() is None:
                try:
                    dumped = subprocess.run([str(self.jcmd), str(server.pid), "JFR.dump", "name=refill",
                                             f"filename={self.output / 'refill.jfr'}"],
                                            capture_output=True, text=True, timeout=30)
                    (self.output / "jfr-dump.log").write_text(dumped.stdout + dumped.stderr, encoding="utf-8")
                    if dumped.returncode: failure = RuntimeError("Server JFR dump failed")
                except (OSError, subprocess.TimeoutExpired) as error:
                    failure = error
            super().close()
            if failure is not None: raise failure

    module.Preview = ObservedPreview
    return module


if __name__ == "__main__":
    run_acceptance.load_preview = observed_preview
    run_acceptance.main()
