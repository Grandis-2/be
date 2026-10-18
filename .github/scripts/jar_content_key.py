#!/usr/bin/env python3
"""bootJar 의 내용 해시(jar 키)를 출력한다. release.yml 이 버전을 올릴지와 내용 키를 이 값으로 정한다.

  python3 .github/scripts/jar_content_key.py <서비스>/build/libs/<이름>.jar

- build-info.properties 는 뺀다. 버전 · 커밋이 들어 있어, 넣으면 코드가 같아도 키가 바뀌고 버전이 오른다.
  release 가 판정 뒤 버전을 넣어 jar 를 다시 만들어도 키는 그대로다. jar 에서 찾지 못하면 실패한다 —
  Boot 가 위치를 옮기면 조용히 키에 섞여 커밋마다 버전이 오르기 때문이다.
- 나머지 항목은 이름과 내용 바이트로 해시한다. 이름 순으로 정렬해 jar 안의 순서에 흔들리지 않는다.
"""
import hashlib
import sys
import zipfile

EXCLUDED = {
    "BOOT-INF/classes/META-INF/build-info.properties",
    "META-INF/build-info.properties",
}


def content_key(path):
    digest = hashlib.sha256()
    excluded = 0
    with zipfile.ZipFile(path) as jar:
        for info in sorted(jar.infolist(), key=lambda i: i.filename):
            if info.filename in EXCLUDED:
                excluded += 1
                continue
            digest.update(info.filename.encode("utf-8") + b"\0")
            digest.update(hashlib.sha256(jar.read(info)).digest())
    if excluded == 0:
        raise ValueError(f"build-info.properties 가 없다(위치가 바뀌었으면 EXCLUDED 를 고친다): {path}")
    return digest.hexdigest()


if __name__ == "__main__":
    if len(sys.argv) != 2:
        sys.exit("사용: jar_content_key.py <jar>")
    print(content_key(sys.argv[1]))
