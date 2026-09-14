import importlib.util
from pathlib import Path
import unittest
import asyncio
import json
import tempfile
from types import SimpleNamespace
from unittest.mock import patch

WORKER = Path(__file__).resolve().parents[1] / 'finrobot_worker.py'


class EvidenceAdapterTest(unittest.TestCase):
    def test_cli_entrypoint_reports_domain_errors_after_all_definitions(self):
        # 回归：入口块必须在所有定义之后执行；否则以脚本方式运行时会先抛 NameError。
        import subprocess
        import sys
        request = {'security': {'code': 'invalid'}, 'snapshot': {},
                   'model': {'apiKey': 'fixture', 'baseUrl': 'http://127.0.0.1:1/v1', 'model': 'fixture'}}
        with tempfile.TemporaryDirectory() as directory:
            completed = subprocess.run([sys.executable, str(WORKER), directory],
                input=json.dumps(request), text=True, capture_output=True, timeout=120)
            self.assertEqual(completed.returncode, 1)
            error = json.loads((Path(directory) / 'error.json').read_text(encoding='utf-8'))
            self.assertEqual(error['errorType'], 'ValueError')

    def test_official_sdk_calls_configured_chat_endpoint_for_every_section(self):
        from http.server import BaseHTTPRequestHandler, ThreadingHTTPServer
        import threading
        spec = importlib.util.spec_from_file_location('finrobot_worker', WORKER)
        worker = importlib.util.module_from_spec(spec)
        spec.loader.exec_module(worker)
        received = []
        class Handler(BaseHTTPRequestHandler):
            def log_message(self, *args):
                pass
            def do_POST(self):
                body = json.loads(self.rfile.read(int(self.headers['Content-Length'])))
                received.append((self.path, body))
                # SDK 仍负责结构化输出验证；兼容接口只需支持 json_object。
                prompt = body['messages'][0]['content']
                field = prompt.split('JSON 字段名：')[-1].split()[0]
                content = json.dumps({field: '本专题资料不足，无法确认。'}, ensure_ascii=False)
                result = {'id': 'fixture', 'object': 'chat.completion', 'created': 1, 'model': 'fixture',
                          'choices': [{'index': 0, 'message': {'role': 'assistant', 'content': content}, 'finish_reason': 'stop'}]}
                data = json.dumps(result).encode()
                self.send_response(200); self.send_header('Content-Type', 'application/json')
                self.send_header('Content-Length', str(len(data))); self.end_headers(); self.wfile.write(data)
        server = ThreadingHTTPServer(('127.0.0.1', 0), Handler)
        thread = threading.Thread(target=server.serve_forever, daemon=True); thread.start()
        try:
            with tempfile.TemporaryDirectory() as directory:
                result = asyncio.run(worker.run_research({'security': {'code': '600519'}, 'snapshot': {},
                    'model': {'apiKey': 'fixture', 'baseUrl': f'http://127.0.0.1:{server.server_port}/v1', 'model': 'fixture'}}, Path(directory)))
                self.assertEqual(result['status'], 'COMPLETED')
                self.assertEqual(len(received), 8)
                self.assertTrue(all(path == '/v1/chat/completions' for path, _ in received))
                self.assertTrue(all(body['response_format']['type'] == 'json_object' for _, body in received))
        finally:
            server.shutdown(); server.server_close(); thread.join()

    def test_annual_evidence_preserves_zero_missing_and_provenance(self):
        spec = importlib.util.spec_from_file_location('finrobot_worker', WORKER)
        worker = importlib.util.module_from_spec(spec)
        spec.loader.exec_module(worker)
        provenance = {'provider': 'fixture', 'sourceUrl': 'https://example.org/report'}
        evidence = worker.prepare_evidence({
            'security': {'code': '600519'}, 'snapshot': {},
            'financialHistory': {'status': 'HEALTHY', 'provenance': provenance,
                'payload': {'security': {'code': '600519'}, 'periods': [
                    {'reportPeriod': '2024-12-31', 'operatingRevenue': 100, 'operatingCost': 0},
                    {'reportPeriod': '2025-09-30', 'operatingRevenue': 80},
                    {'reportPeriod': '2025-12-31', 'operatingRevenue': 120},
                ]}}})
        self.assertEqual(evidence['annualRows'][0]['operatingCost'], 0)
        self.assertIsNone(evidence['annualRows'][1].get('operatingCost'))
        self.assertEqual(len(evidence['annualRows']), 2)
        self.assertEqual(evidence['financialHistory']['provenance'], provenance)
        self.assertEqual(evidence['valuation']['status'], 'UNAVAILABLE')
        self.assertNotIn('ebitda', evidence['annualRows'][0])

    def test_runs_eight_official_agents_and_keeps_distinct_sections(self):
        spec = importlib.util.spec_from_file_location('finrobot_worker', WORKER)
        worker = importlib.util.module_from_spec(spec)
        spec.loader.exec_module(worker)
        calls = []

        async def fake_run(agent, prompt, **kwargs):
            calls.append(agent.name)
            field = next(iter(agent.output_type.model_fields))
            return SimpleNamespace(final_output=agent.output_type(**{field: agent.name + ' 独立研究'}))

        with tempfile.TemporaryDirectory() as directory:
            with patch('agents.Runner.run', side_effect=fake_run):
                result = asyncio.run(worker.run_research({
                    'security': {'code': '600519'}, 'snapshot': {},
                    'model': {'apiKey': 'fixture', 'baseUrl': 'http://127.0.0.1:1/v1', 'model': 'fixture'},
                }, Path(directory)))
            self.assertEqual(len(calls), 8)
            self.assertEqual(len(set(calls)), 8)
            self.assertNotEqual(result['sections']['valuation_overview']['text'],
                                result['sections']['competitor_analysis']['text'])
            self.assertEqual(result['engine'], 'official-finrobot-equity')
            self.assertNotIn('apiKey', json.dumps(result))
            self.assertTrue((Path(directory) / 'report.html').is_file())


if __name__ == '__main__':
    unittest.main()
