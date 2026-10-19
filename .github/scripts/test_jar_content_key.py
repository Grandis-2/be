"""jar_content_key.content_key 시험. python3 -m unittest discover -s .github/scripts -p 'test_*.py'"""
import os
import tempfile
import unittest
import zipfile

from jar_content_key import check_unversioned, content_key, with_project_version

# Boot 4 는 build-info 를 jar 최상위 META-INF 에 둔다. 예전 위치(BOOT-INF/classes)도 함께 본다
BUILD_INFO_PATHS = ["META-INF/build-info.properties", "BOOT-INF/classes/META-INF/build-info.properties"]
BASE = "0.0.1-SNAPSHOT"
INFO = (BUILD_INFO_PATHS[0], b"build.version=0.0.1\n")



class ContentKeyTest(unittest.TestCase):
    def setUp(self):
        self.dir = tempfile.TemporaryDirectory()

    def tearDown(self):
        self.dir.cleanup()

    @staticmethod
    def mf(*lines):
        return "META-INF/MANIFEST.MF", b"".join(line + b"\r\n" for line in lines)

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
                self.assertEqual(content_key(a, BASE), content_key(b, BASE))

    def test_항목_순서가_달라도_같은_키(self):
        info = (BUILD_INFO_PATHS[0], b"build.version=0.0.1\n")
        a = self.jar("a.jar", [("A.class", b"A"), ("B.class", b"B"), info])
        b = self.jar("b.jar", [info, ("B.class", b"B"), ("A.class", b"A")])
        self.assertEqual(content_key(a, BASE), content_key(b, BASE))

    # 실제 bootJar 의 MANIFEST(catalog). Boot 는 Implementation-Version 을 주 섹션 끝에 붙이고, build.gradle 이 값을 정하면 앞쪽에 들어간다
    BOOT = [b"Manifest-Version: 1.0", b"Main-Class: org.springframework.boot.loader.launch.JarLauncher",
            b"Start-Class: com.grandis.nova.catalog.CatalogApplication", b"Spring-Boot-Version: 4.1.1",
            b"Spring-Boot-Classes: BOOT-INF/classes/", b"Spring-Boot-Lib: BOOT-INF/lib/",
            b"Spring-Boot-Classpath-Index: BOOT-INF/classpath.idx", b"Spring-Boot-Layers-Index: BOOT-INF/layers.idx",
            b"Build-Jdk-Spec: 25", b"Implementation-Title: catalog"]

    def test_운영_jar_의_MANIFEST_는_바이트가_그대로다(self):
        # 지금까지 ECR 에 쌓인 키와 같은 값이 나와야 한다 — 다르면 모든 서비스의 버전이 한 번 오른다
        raw = b"".join(l + b"\r\n" for l in self.BOOT + [b"Implementation-Version: 0.0.1-SNAPSHOT", b""])
        self.assertEqual(raw, with_project_version(raw, BASE))

    def test_버전을_넣은_MANIFEST_는_자리와_값이_운영_jar_와_같아진다(self):
        ours = self.BOOT[:1] + [b"Implementation-Version: 0.0.9"] + self.BOOT[1:] + [b""]
        boot = self.BOOT + [b"Implementation-Version: 0.0.1-SNAPSHOT", b""]
        self.assertEqual(b"".join(l + b"\r\n" for l in boot), with_project_version(b"".join(l + b"\r\n" for l in ours), BASE))

    def test_빈_줄이_없어도_주_섹션_끝으로_옮긴다(self):
        self.assertEqual(b"A: 1\r\nB: 2\r\nImplementation-Version: 0.0.1-SNAPSHOT\r\n",
                         with_project_version(b"A: 1\r\nImplementation-Version: 0.0.9\r\nB: 2\r\n", BASE))

    def test_마지막_줄에_줄_끝이_없어도_줄이_붙지_않는다(self):
        self.assertEqual(b"A: 1\r\nB: 2\r\nImplementation-Version: 0.0.1-SNAPSHOT\r\n",
                         with_project_version(b"A: 1\r\nImplementation-Version: 0.0.9\r\nB: 2", BASE))

    def test_이어지는_줄과_함께_옮기고_Name_섹션은_바이트_그대로(self):
        name = b"\r\nName: lib/\r\nImplementation-Version: 9.9\r\n"
        raw = b"A: 1\r\nImplementation-Version: 0.0.9-long\r\n tail\r\nB: 2\r\n  more\r\n" + name
        self.assertEqual(b"A: 1\r\nB: 2\r\n  more\r\nImplementation-Version: 0.0.1-SNAPSHOT\r\n" + name, with_project_version(raw, BASE))

    def test_공백만_있는_줄은_이어지는_줄이다(self):
        raw = b"A: 1\r\n \r\nImplementation-Version: 0.0.1-SNAPSHOT\r\n"
        self.assertEqual(raw, with_project_version(raw, BASE))

    def test_속성이_없거나_비면_그대로(self):
        for raw in (b"", b"A: 1\r\n\r\nName: x\r\nImplementation-Version: 1\r\n"):
            with self.subTest(raw=raw):
                self.assertEqual(raw, with_project_version(raw, BASE))

    def test_버전을_넣지_않았다고_단언한_jar_의_MANIFEST_가_바뀌면_실패(self):
        ok = self.jar("ok.jar", [self.mf(*self.BOOT, b"Implementation-Version: 0.0.1-SNAPSHOT"), INFO])
        check_unversioned(ok, BASE)
        moved = self.jar("moved.jar", [self.mf(self.BOOT[0], b"Implementation-Version: 0.0.1-SNAPSHOT", *self.BOOT[1:]), INFO])
        with self.assertRaises(ValueError):
            check_unversioned(moved, BASE)
        missing = self.jar("missing.jar", [self.mf(*self.BOOT), INFO])
        with self.assertRaises(ValueError):
            check_unversioned(missing, BASE)

    def test_버전을_넣지_않은_MANIFEST_는_바이트가_그대로다(self):
        # 이 줄이 키에 들기 전부터 쌓인 ECR 의 키와 같은 값이 나와야 한다 — 다르면 모든 서비스의 버전이 한 번 오른다
        raw = b"Manifest-Version: 1.0\r\nStart-Class: A\r\nImplementation-Version: 0.0.1-SNAPSHOT\r\n\r\n"
        self.assertEqual(raw, with_project_version(raw, BASE))

    def test_줄_끝과_콜론_뒤_공백은_원본대로_둔다(self):
        for raw in (b"Implementation-Version:0.0.1-SNAPSHOT\n", b"implementation-version:  0.0.1-SNAPSHOT\r\n", b"Implementation-Version: 0.0.1-SNAPSHOT"):
            with self.subTest(raw=raw):
                self.assertEqual(raw, with_project_version(raw, BASE))
        self.assertEqual(b"Implementation-Version:0.0.1-SNAPSHOT\n", with_project_version(b"Implementation-Version:0.0.7\n", BASE))

    def test_MANIFEST_의_버전만_다르면_같은_키(self):
        a = self.jar("a.jar", [self.mf(b"Start-Class: A", b"Implementation-Version: 0.0.1-SNAPSHOT"), INFO])
        b = self.jar("b.jar", [self.mf(b"Start-Class: A", b"Implementation-Version: 0.0.12"), INFO])
        self.assertEqual(content_key(a, BASE), content_key(b, BASE))

    def test_MANIFEST_의_버전_밖이_바뀌면_다른_키(self):
        a = self.jar("a.jar", [self.mf(b"Start-Class: A", b"Implementation-Version: 0.0.1"), INFO])
        b = self.jar("b.jar", [self.mf(b"Start-Class: B", b"Implementation-Version: 0.0.1"), INFO])
        self.assertNotEqual(content_key(a, BASE), content_key(b, BASE))

    def test_이어지는_줄까지_버전으로_본다(self):
        a = self.jar("a.jar", [self.mf(b"Implementation-Version: 0.0.1", b" Xlong", b"Start-Class: A"), INFO])
        b = self.jar("b.jar", [self.mf(b"Implementation-Version: 0.0.2", b"Start-Class: A"), INFO])
        self.assertEqual(content_key(a, BASE), content_key(b, BASE))

    def test_Name_섹션의_버전은_키에_남는다(self):
        main = (b"Implementation-Version: 0.0.1", b"", b"Name: lib/")
        a = self.jar("a.jar", [self.mf(*main, b"Implementation-Version: 1.0"), INFO])
        b = self.jar("b.jar", [self.mf(*main, b"Implementation-Version: 2.0"), INFO])
        self.assertNotEqual(content_key(a, BASE), content_key(b, BASE))

    def test_build_info_가_없으면_실패(self):
        with self.assertRaises(ValueError):
            content_key(self.jar("a.jar", [("A.class", b"A"), ("BOOT-INF/classes/build-info.properties", b"x")]), BASE)

    def test_클래스가_바뀌면_다른_키(self):
        a = self.jar("a.jar", [("A.class", b"A"), (BUILD_INFO_PATHS[0], b"build.version=0.0.1\n")])
        b = self.jar("b.jar", [("A.class", b"A2"), (BUILD_INFO_PATHS[0], b"build.version=0.0.1\n")])
        self.assertNotEqual(content_key(a, BASE), content_key(b, BASE))


if __name__ == "__main__":
    unittest.main()
