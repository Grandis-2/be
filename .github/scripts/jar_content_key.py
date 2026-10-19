#!/usr/bin/env python3
"""bootJar 의 내용 해시(jar 키)를 출력한다. release.yml 이 버전을 올릴지와 내용 키를 이 값으로 정한다.

  python3 .github/scripts/jar_content_key.py <서비스>/build/libs/<이름>.jar <프로젝트 버전>

release 는 판정 뒤 버전을 넣어 jar 를 다시 만든다(build.gradle). 버전이 드는 두 곳이 키를 흔들지 않게 한다 —
흔들면 코드가 같아도 키가 바뀌고 버전이 오른다.
- build-info.properties 는 뺀다(커밋도 든다). jar 에서 찾지 못하면 실패한다 — Boot 가 위치를 옮기면 조용히 키에 섞이기 때문이다.
- MANIFEST.MF 주 섹션(첫 빈 줄 전)의 Implementation-Version 은 값을 프로젝트 버전(build.gradle 의 version)으로 되돌려 해시한다.
  버전을 넣지 않은 jar 는 원래 그 값이라 바이트가 그대로다 — 이 줄이 들기 전부터 쌓인 ECR 의 키와 같은 값이 나온다.
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
    """MANIFEST 주 섹션의 Implementation-Version 값을 프로젝트 버전으로 되돌린다. 긴 값은 공백으로 시작하는 다음 줄로 이어진다."""
    kept, skipping, main = [], False, True
    for line in manifest.splitlines(keepends=True):
        if line.startswith(b" "):
            if not skipping:
                kept.append(line)
            continue
        if not line.strip():
            main = False
        skipping = main and line.split(b":", 1)[0].strip().lower() == VERSION_ATTRIBUTE
        if skipping:
            # 이름 · 콜론 뒤 공백 · 줄 끝은 원본 그대로 둔다 — 값이 이미 프로젝트 버전이면 바이트가 같아야 한다
            name, rest = line.split(b":", 1)
            value = rest.rstrip(b"\r\n")
            space = value[:len(value) - len(value.lstrip(b" "))]
            kept.append(name + b":" + space + project_version.encode("utf-8") + rest[len(value):])
        else:
            kept.append(line)
    return b"".join(kept)


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
    if len(sys.argv) != 3 or not sys.argv[2]:
        sys.exit("사용: jar_content_key.py <jar> <프로젝트 버전>")
    print(content_key(sys.argv[1], sys.argv[2]))
