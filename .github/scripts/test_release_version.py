"""release_version.decide 시험. python3 -m unittest discover -s .github/scripts -p 'test_*.py'"""
import json
import os
import subprocess
import sys
import tempfile
import unittest

from release_version import decide

J1, J2 = "a" * 64, "b" * 64
C1, C2, C3 = "1" * 64, "2" * 64, "3" * 64
SHA = "f" * 40


def image(digest, *tags):
    return {"imageDigest": digest, "imageTags": list(tags)}


def published(digest, n, m, jar, content, *extra):
    return image(digest, f"0.0.{n}-r{m}", f"jar-{jar}-r{m}", f"content-{content}-r{m}", *extra)


class DecideTest(unittest.TestCase):
    def test_처음이면_0_0_1_r1(self):
        # 키 공식이 바뀌기 전의 이미지는 버전 · jar · content(-rM) 태그가 없어 번호 계산에 끼지 않는다
        old = image("sha256:old", "content-" + C1, "e" * 40, "latest")
        got = decide([old], J1, C1, SHA)
        self.assertEqual(("new", "0.0.1-r1", None, True), (got["kind"], got["tag"], got["digest"], got["push_sha"]))

    def test_내용_키가_있으면_그_이미지의_태그_그대로(self):
        images = [published("sha256:x", 1, 1, J1, C1), published("sha256:y", 1, 2, J1, C2, "latest")]
        got = decide(images, J1, C1, SHA)
        self.assertEqual(("same", "0.0.1-r1", "sha256:x"), (got["kind"], got["tag"], got["digest"]))

    def test_jar_같고_내용_키_다르면_리비전만(self):
        images = [published("sha256:x", 1, 1, J1, C1)]
        got = decide(images, J1, C2, SHA)
        self.assertEqual(("revision", "0.0.1", 2), (got["kind"], got["version"], got["revision"]))

    def test_jar_다르면_다음_버전_r1(self):
        images = [published("sha256:x", 1, 1, J1, C1), published("sha256:y", 1, 2, J1, C2)]
        got = decide(images, J2, C3, SHA)
        self.assertEqual(("new", "0.0.2-r1"), (got["kind"], got["tag"]))

    def test_r1_이_지워져도_남은_리비전으로_같은_버전(self):
        images = [published("sha256:y", 3, 2, J1, C2), published("sha256:z", 4, 1, J2, C3)]
        got = decide(images, J1, C1, SHA)
        self.assertEqual(("revision", "0.0.3-r3"), (got["kind"], got["tag"]))

    def test_jar_의_이미지가_모두_지워지면_새_버전(self):
        images = [published("sha256:z", 4, 1, J2, C3)]
        got = decide(images, J1, C1, SHA)
        self.assertEqual(("new", "0.0.5-r1"), (got["kind"], got["tag"]))

    def test_다른_리비전이_최대_M_을_차지하면_그다음(self):
        # 되돌림으로 r1 을 다시 쓰는 사이 r2 가 올라가 있었다 — M 은 jar 태그가 아니라 버전 태그의 최대에서 잇는다
        images = [published("sha256:x", 1, 1, J1, C1), published("sha256:y", 1, 2, J1, C2)]
        got = decide(images, J1, C3, SHA)
        self.assertEqual("0.0.1-r3", got["tag"])

    def test_강제면_내용_키가_있어도_리비전(self):
        images = [published("sha256:x", 2, 1, J1, C1)]
        got = decide(images, J1, C1, SHA, forced=True)
        self.assertEqual(("revision", "0.0.2-r2"), (got["kind"], got["tag"]))

    def test_강제로_다시_만든_이미지가_있으면_그것을_쓴다(self):
        images = [published("sha256:x", 2, 1, J1, C1), published("sha256:y", 2, 2, J1, C1)]
        got = decide(images, J1, C1, SHA)
        self.assertEqual(("same", "0.0.2-r2", "sha256:y"), (got["kind"], got["tag"], got["digest"]))

    def test_중간에_끊긴_이미지는_번호만_차지한다(self):
        # 올리다 끊겨 content 태그가 없는 r1 — 다시 쓰지 않고 r2 로 올린다. 커밋 태그는 r1 에 남아 있어 건너뛴다
        broken = image("sha256:x", "0.0.1-r1", f"jar-{J1}-r1", SHA)
        got = decide([broken], J1, C1, SHA)
        self.assertEqual(("revision", "0.0.1-r2", False), (got["kind"], got["tag"], got["push_sha"]))

    def test_버전_태그만_붙고_끊긴_이미지는_번호를_건너뛴다(self):
        # jar 태그 전에 끊기면 그 이미지의 jar 를 알 수 없다. 번호는 불변 태그와 겹치지 않게 비워 두고 다음을 쓴다(빈 번호가 생긴다)
        images = [published("sha256:x", 4, 1, J1, C1), image("sha256:y", "0.0.5-r1")]
        got = decide(images, J2, C2, SHA)
        self.assertEqual(("new", "0.0.6-r1"), (got["kind"], got["tag"]))

    def test_리비전_중_버전_태그만_붙고_끊기면_M_만_건너뛴다(self):
        images = [published("sha256:x", 3, 1, J1, C1), image("sha256:y", "0.0.3-r2")]
        got = decide(images, J1, C2, SHA)
        self.assertEqual(("revision", "0.0.3-r3"), (got["kind"], got["tag"]))

    def test_강제여도_jar_키가_없으면_새_버전(self):
        images = [published("sha256:x", 2, 1, J1, C1)]
        got = decide(images, J2, C2, SHA, forced=True)
        self.assertEqual(("new", "0.0.3-r1"), (got["kind"], got["tag"]))

    def test_그대로여도_커밋_태그가_이미_있으면_붙이지_않는다(self):
        images = [published("sha256:x", 1, 1, J1, C1, SHA)]
        got = decide(images, J1, C1, SHA)
        self.assertEqual(("same", False), (got["kind"], got["push_sha"]))

    def test_태그_없는_이미지는_무시한다(self):
        images = [{"imageDigest": "sha256:u"}, published("sha256:x", 1, 1, J1, C1)]
        got = decide(images, J1, C1, SHA)
        self.assertEqual(("same", "0.0.1-r1"), (got["kind"], got["tag"]))

    def test_v_가_붙은_태그는_버전으로_보지_않는다(self):
        # 버전 태그는 jar 의 build.version 과 같은 표기다(v 없음). v 를 받아들이면 번호 계산에 섞인다
        got = decide([image("sha256:o", "v0.0.9-r1")], J1, C1, SHA)
        self.assertEqual(("new", "0.0.1-r1"), (got["kind"], got["tag"]))

    def test_번호는_정수로_비교한다(self):
        images = [published("sha256:x", 9, 1, J1, C1), published("sha256:y", 10, 1, J2, C2)]
        got = decide(images, "c" * 64, C3, SHA)
        self.assertEqual("0.0.11-r1", got["tag"])

    def test_같은_이미지가_두_리비전으로_올라가도_표지의_M_으로_고른다(self):
        # 강제로 다시 만든 이미지의 digest 가 같으면 한 이미지에 r1 · r2 표지가 함께 붙는다
        twice = published("sha256:x", 2, 1, J1, C1)
        twice["imageTags"] += ["0.0.2-r2", f"jar-{J1}-r2", f"content-{C1}-r2"]
        same = decide([twice], J1, C1, SHA)
        self.assertEqual(("same", "0.0.2-r2"), (same["kind"], same["tag"]))
        revision = decide([twice], J1, C2, SHA)
        self.assertEqual(("revision", "0.0.2-r3"), (revision["kind"], revision["tag"]))

    def test_버전_태그_없이_내용_키_태그만_있으면_실패(self):
        with self.assertRaises(ValueError):
            decide([image("sha256:x", f"content-{C1}-r1")], J1, C1, SHA)

    def test_표지와_짝인_버전_태그가_없으면_실패(self):
        broken = image("sha256:x", "0.0.1-r1", f"jar-{J1}-r2", f"content-{C1}-r2")
        for content in (C1, C2):
            with self.subTest(content=content), self.assertRaises(ValueError):
                decide([broken], J1, content, SHA)

    def test_jar_키_하나에_버전이_둘이면_실패(self):
        images = [published("sha256:x", 1, 1, J1, C1), published("sha256:y", 2, 1, J1, C2)]
        with self.assertRaises(ValueError):
            decide(images, J1, C3, SHA)


class CliTest(unittest.TestCase):
    """release.yml 이 쓰는 그대로 — 빈 저장소에서 JSON 한 줄을 내고, 인자가 틀리면 0 이 아닌 코드로 끝난다."""

    script = os.path.join(os.path.dirname(os.path.abspath(__file__)), "release_version.py")

    def run_cli(self, *args):
        return subprocess.run([sys.executable, self.script, *args], capture_output=True, text=True)

    def test_빈_저장소(self):
        with tempfile.NamedTemporaryFile("w", suffix=".json", delete=False) as f:
            json.dump({"imageDetails": []}, f)
        try:
            out = self.run_cli(f.name, J1, C1, SHA, "normal")
        finally:
            os.unlink(f.name)
        self.assertEqual(0, out.returncode, out.stderr)
        got = json.loads(out.stdout)
        self.assertEqual(("new", "0.0.1-r1", None, 1), (got["kind"], got["tag"], got["digest"], got["revision"]))

    def test_모드가_틀리면_실패(self):
        self.assertNotEqual(0, self.run_cli("x.json", J1, C1, SHA, "--forced").returncode)


if __name__ == "__main__":
    unittest.main()
