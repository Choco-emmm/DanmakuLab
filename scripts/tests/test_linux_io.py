"""Linux 诊断编排的关键回收检查；不启动 Java，不访问用户已有服务。"""
import importlib.util
from pathlib import Path
import socket
import subprocess
import sys
import time
import unittest
from unittest.mock import Mock


SPEC = importlib.util.spec_from_file_location("linux_io", Path(__file__).resolve().parents[1] / "run/linux-io.py")
lab = importlib.util.module_from_spec(SPEC)
SPEC.loader.exec_module(lab)


class DiagnosticSafetyTest(unittest.TestCase):
    def test_missing_gauge_cannot_pass_cleanup(self):
        """指标接口返回成功但字段缺失时，未知连接/预算不能被当成已经回收。"""
        values = lab.gauge_values({"metrics": []})
        self.assertTrue(all(value is None for value in values.values()))
        self.assertFalse(all(value == 0 for value in values.values()))

    def test_failed_collector_command_is_detected(self):
        """采集命令非零退出或超时都必须成为失败证据，不能只看是否生成了文件。"""
        self.assertTrue(lab.command_failed({"exitCode": 1, "stdout": ""}))
        self.assertTrue(lab.command_failed({"error": "timeout"}))
        self.assertFalse(lab.command_failed({"exitCode": 0, "stdout": "evidence"}))

    def test_occupied_port_is_rejected_without_touching_listener(self):
        """占用端口时预检应拒绝；测试自己的监听器继续可用，证明没有接管已有服务。"""
        with socket.socket() as listener:
            listener.bind(("127.0.0.1", 0))
            listener.listen()
            port = listener.getsockname()[1]
            with self.assertRaises(OSError):
                lab.port_available(port)
            with socket.create_connection(("127.0.0.1", port), timeout=1):
                accepted, _ = listener.accept()
                accepted.close()

    def test_only_owned_child_is_stopped(self):
        """模拟实验异常后的清理：停止目标子进程，同时另一自建对照进程仍存活。"""
        target = subprocess.Popen([sys.executable, "-c", "import time; time.sleep(30)"])
        other = subprocess.Popen([sys.executable, "-c", "import time; time.sleep(30)"])
        try:
            time.sleep(.1)
            lab.stop_owned(target)
            self.assertIsNotNone(target.poll())
            self.assertIsNone(other.poll())
        finally:
            lab.stop_owned(target)
            lab.stop_owned(other)

    def test_client_stop_error_does_not_skip_server_cleanup(self):
        """注入第一个回收失败，验证真实的另一个自建进程仍退出，且失败进入状态证据。"""
        unkillable = Mock()
        unkillable.poll.return_value = None
        unkillable.terminate.side_effect = PermissionError("模拟客户端终止失败")
        server = subprocess.Popen([sys.executable, "-c", "import time; time.sleep(30)"])
        try:
            result = lab.finish_owned(unkillable, server, Mock())
            self.assertIsNotNone(server.poll())
            self.assertFalse(result["serverStillRunning"])
            self.assertTrue(result["clientStillRunning"])
            self.assertEqual(result["cleanupErrors"][0]["resource"], "clientFinalExitCode")
        finally:
            lab.stop_owned(server)

    @unittest.skipUnless(sys.platform.startswith("linux"), "验证 Linux TIME_WAIT 地址重用")
    def test_closed_server_time_wait_does_not_block_next_case(self):
        """服务已退出、旧连接留在 TIME_WAIT 时应允许下一组；与仍监听的端口区别处理。"""
        with socket.socket() as listener:
            listener.setsockopt(socket.SOL_SOCKET, socket.SO_REUSEADDR, 1)
            listener.bind(("127.0.0.1", 0))
            listener.listen()
            port = listener.getsockname()[1]
            with socket.create_connection(("127.0.0.1", port), timeout=1) as client:
                accepted, _ = listener.accept()
                accepted.close()
                self.assertEqual(client.recv(1), b"")
        lab.port_available(port)


if __name__ == "__main__":
    unittest.main()
