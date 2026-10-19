#!/usr/bin/env python3
"""bootJar 의 내용 해시(jar 키)를 출력한다. release.yml 이 버전을 올릴지와 내용 키를 이 값으로 정한다.

  python3 .github/scripts/jar_content_key.py <서비스>/build/libs/<이름>.jar <프로젝트 버전> [--unversioned]

--unversioned: 버전을 넣지 않은 jar 라고 단언한다. MANIFEST 를 맞춰서 바이트가 바뀌면(전제가 깨지면) 키를 내지 않고 실패한다.

release 는 판정 뒤 버전을 넣어 jar 를 다시 만든다(build.gradle). 버전이 드는 두 곳이 키를 흔들지 않게 한다 —
흔들면 코드가 같아도 키가 바뀌고 버전이 오른다.
- build-info.properties 는 뺀다(커밋도 든다). jar 에서 찾지 못하면 실패한다 — Boot 가 위치를 옮기면 조용히 키에 섞이기 때문이다.
- MANIFEST.MF 주 섹션(첫 빈 줄 전)의 Implementation-Version 은 Boot 가 두는 자리(주 섹션 끝)에 프로젝트 버전(build.gradle 의 version)으로
  다시 놓고 해시한다. 버전을 넣지 않은 운영 jar 는 원래 그 자리 · 그 값이라 바이트가 그대로다 — 지금까지 ECR 에 쌓인 키와 같은 값이 나온다.
  Name: 섹션의 같은 이름 속성은 의존성 값이라 그대로 둔다.
- 나머지 항목은 이름과 내용 바이트로 해시한다. 이름 순으로 정렬해 jar 안의 순서에 흔들리지 않는다.
"""
import hashlib
import sys
import zipfile

EXCLUDED = {
    "BOOT-INF/classes/META-INF/build-info.properties",
    "META-INF/build-info.properties",
}
MANIFEST = "META-INF/MANIFEST.MF"
VERSION_ATTRIBUTE = b"implementation-version"


def with_project_version(manifest, project_version):
    """MANIFEST 주 섹션의 Implementation-Version 을 주 섹션 끝에 프로젝트 버전으로 다시 놓는다.

    Boot 는 그 속성을 주 섹션 끝에 붙이고, build.gradle 이 값을 정하면 앞쪽에 들어간다. 자리 · 값을 맞춰야 버전을 넣지 않은
    jar 의 바이트가 그대로 남는다(그 전제는 check_unversioned 가 본다). 이름 · 콜론 뒤 공백 · 줄 끝은 원본 그대로 쓴다.
    긴 값은 공백으로 시작하는 다음 줄로 이어지고, 주 섹션은 빈 줄에서 끝난다.
    """
    lines = manifest.splitlines(keepends=True)
    head, version_line, skipping = [], None, False
    end = len(lines)
    for i, line in enumerate(lines):
        if not line.rstrip(b"\r\n"):
            end = i
            break
        if line.startswith(b" "):
            if not skipping:
                head.append(line)
            continue
        name, _, rest = line.partition(b":")
        skipping = name.strip().lower() == VERSION_ATTRIBUTE
        if skipping:
            value = rest.rstrip(b"\r\n")
            space = value[:len(value) - len(value.lstrip(b" "))]
            version_line = name + b":" + space + project_version.encode("utf-8") + rest[len(value):]
        else:
            head.append(line)
    if version_line is None:
        return manifest
    eol = version_line[len(version_line.rstrip(b"\r\n")):] or b"\r\n"
    if head and not head[-1].endswith((b"\n", b"\r")):
        head[-1] += eol
    return b"".join(head) + version_line + b"".join(lines[end:])


def check_unversioned(path, project_version):
    """버전을 넣지 않은 jar 는 주 섹션 끝에 Implementation-Version: <프로젝트 버전> 이 있고, MANIFEST 를 맞춰도 바이트가 그대로여야 한다.

    with_project_version 이 기대는 Boot 의 배치(속성의 자리 · 값)다. 어긋나면 버전을 넣은 jar 와 넣지 않은 jar 를 같게 맞추지 못해,
    release 가 다시 만든 jar 의 키가 판정한 키와 달라지거나 버전이 MANIFEST 에 맞게 들지 않는다. 그 가정이 깨진 채 나아가지 않게 멈춘다.
    """
    with zipfile.ZipFile(path) as jar:
        manifest = jar.read(MANIFEST)
    if (with_project_version(manifest, project_version) != manifest
            or (f"Implementation-Version: {project_version}").encode("utf-8") not in manifest.split(b"\r\n\r\n", 1)[0]):
        raise ValueError(f"{path} 의 MANIFEST 가 Implementation-Version: {project_version} 를 주 섹션 끝에 두지 않는다 — "
                         "jar_content_key.py 의 맞추는 방식을 Boot 에 맞게 고친다(고치기 전 키와 같은지 본다)")


def content_key(path, project_version):
    digest = hashlib.sha256()
    excluded = 0
    with zipfile.ZipFile(path) as jar:
        for info in sorted(jar.infolist(), key=lambda i: i.filename):
            if info.filename in EXCLUDED:
                excluded += 1
                continue
            data = jar.read(info)
            if info.filename == MANIFEST:
                data = with_project_version(data, project_version)
            digest.update(info.filename.encode("utf-8") + b"\0")
            digest.update(hashlib.sha256(data).digest())
    if excluded == 0:
        raise ValueError(f"build-info.properties 가 없다(위치가 바뀌었으면 EXCLUDED 를 고친다): {path}")
    return digest.hexdigest()


if __name__ == "__main__":
    args = sys.argv[1:]
    unversioned = args[2:] == ["--unversioned"]
    if len(args) not in (2, 3) or not args[1] or (len(args) == 3 and not unversioned):
        sys.exit("사용: jar_content_key.py <jar> <프로젝트 버전> [--unversioned]")
    if unversioned:
        check_unversioned(args[0], args[1])
    print(content_key(args[0], args[1]))
