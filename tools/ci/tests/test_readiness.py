import http.server
import pathlib
import shutil
import socket
import subprocess
import sys
import tempfile
import threading
import time
import unittest


HELPER = pathlib.Path(__file__).resolve().parents[3] / 'scripts/ci-wait-http.sh'


class Handler(http.server.BaseHTTPRequestHandler):
    def do_GET(self):
        self.send_response(200)
        self.end_headers()
        self.wfile.write(b'private-response-sentinel')

    def log_message(self, *args):
        pass


@unittest.skipUnless(shutil.which('bash') and shutil.which('curl'), 'bash/curl required')
class ReadinessTests(unittest.TestCase):
    def setUp(self):
        self.child = subprocess.Popen([sys.executable, '-c', 'import time; time.sleep(60)'])
        self.addCleanup(self.stop_child)

    def stop_child(self):
        if self.child.poll() is None:
            self.child.terminate()
        self.child.wait(timeout=5)

    def run_helper(self, *args):
        return subprocess.run(['bash', str(HELPER), *map(str, args)],
                              capture_output=True, text=True, timeout=10)

    def closed_url(self):
        with socket.socket() as sock:
            sock.bind(('127.0.0.1', 0))
            return 'http://127.0.0.1:%d' % sock.getsockname()[1]

    def test_real_http_success_without_response_logging(self):
        server = http.server.ThreadingHTTPServer(('127.0.0.1', 0), Handler)
        thread = threading.Thread(target=server.serve_forever)
        thread.start()
        try:
            result = self.run_helper('http://127.0.0.1:%d' % server.server_port,
                                     self.child.pid, 1, 0)
            self.assertEqual(result.returncode, 0, result.stderr)
            self.assertNotIn('private-response-sentinel', result.stdout + result.stderr)
            self.assertIsNone(self.child.poll())
        finally:
            server.shutdown()
            server.server_close()
            thread.join(timeout=5)

    def test_dead_owned_child_fails_early(self):
        self.stop_child()
        start = time.monotonic()
        result = self.run_helper(self.closed_url(), self.child.pid, 30, 1)
        self.assertNotEqual(result.returncode, 0)
        self.assertIn('owned process exited', result.stderr)
        self.assertLess(time.monotonic() - start, 3)

    def test_closed_port_deadline_and_pipefail_log(self):
        with tempfile.TemporaryDirectory() as directory:
            log = pathlib.Path(directory) / 'readiness.log'
            start = time.monotonic()
            result = subprocess.run(
                ['bash', '-o', 'pipefail', '-c',
                 'bash "$1" "$2" "$3" 2 0.05 2>&1 | tee "$4"',
                 'readiness-test', str(HELPER), self.closed_url(),
                 str(self.child.pid), str(log)],
                capture_output=True, text=True, timeout=10)
            self.assertNotEqual(result.returncode, 0)
            self.assertTrue(log.is_file())
            self.assertIn('Readiness deadline exhausted.', log.read_text())
            self.assertLess(time.monotonic() - start, 6)
            self.assertIsNone(self.child.poll())

    def test_invalid_arguments(self):
        for args in [(), ('file:///tmp/x', 1), ('http://localhost', 0),
                     ('http://localhost', self.child.pid, 0),
                     ('http://localhost', self.child.pid, 1, '-1'),
                     ('http://localhost', self.child.pid, 'x')]:
            with self.subTest(args=args):
                self.assertEqual(self.run_helper(*args).returncode, 2)


if __name__ == '__main__':
    unittest.main()
