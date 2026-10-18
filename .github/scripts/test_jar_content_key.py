"""jar_content_key.content_key 시험. python3 -m unittest discover -s .github/scripts -p 'test_*.py'"""
import os
import tempfile
import unittest
import zipfile

from jar_content_key import content_key

# Boot 4 는 build-info 를 jar 최상위 META-INF 에 둔다. 예전 위치(BOOT-INF/classes)도 함께 본다
BUILD_INFO_PATHS = ["META-INF/build-info.properties", "BOOT-INF/classes/META-INF/build-info.properties"]


class ContentKeyTest(unittest.TestCase):
    def setUp(self):
        self.dir = tempfile.TemporaryDirectory()

    def tearDown(self):
        self.dir.cleanup()

    def jar(self, name, entries):
        path = os.path.join(self.dir.name, name)
        with zipfile.ZipFile(path, "w") as z:
            for entry, data in entries:
                z.writestr(entry, data)
        return path

    def test_버전만_다른_build_info_는_같은_키(self):
        for path in BUILD_INFO_PATHS:
            with self.subTest(path=path):
                a = self.jar("a.jar", [("BOOT-INF/classes/A.class", b"A"), (path, b"build.version=0.0.1\nbuild.commit=aaa\n")])
                b = self.jar("b.jar", [("BOOT-INF/classes/A.class", b"A"), (path, b"build.version=0.0.7\nbuild.commit=bbb\n")])
                self.assertEqual(content_key(a), content_key(b))

    def test_항목_순서가_달라도_같은_키(self):
        info = (BUILD_INFO_PATHS[0], b"build.version=0.0.1\n")
        a = self.jar("a.jar", [("A.class", b"A"), ("B.class", b"B"), info])
        b = self.jar("b.jar", [info, ("B.class", b"B"), ("A.class", b"A")])
        self.assertEqual(content_key(a), content_key(b))

    def test_build_info_가_없으면_실패(self):
        with self.assertRaises(ValueError):
            content_key(self.jar("a.jar", [("A.class", b"A"), ("BOOT-INF/classes/build-info.properties", b"x")]))

    def test_클래스가_바뀌면_다른_키(self):
        a = self.jar("a.jar", [("A.class", b"A"), (BUILD_INFO_PATHS[0], b"build.version=0.0.1\n")])
        b = self.jar("b.jar", [("A.class", b"A2"), (BUILD_INFO_PATHS[0], b"build.version=0.0.1\n")])
        self.assertNotEqual(content_key(a), content_key(b))


if __name__ == "__main__":
    unittest.main()
