#!/usr/bin/env python3
"""release 1단계에서 서비스 하나의 판정과 버전을 정한다. 번호는 그 서비스 ECR 저장소의 태그에서만 읽는다.

  release_version.py <describe-images.json> <jar 키> <내용 키> <커밋> <normal|forced>

출력(JSON): kind 가 same(그대로 · 태그만 옮김) · revision(jar 그대로 · 이미지만 새로) · new(새 버전) 중 하나이고,
version(0.0.N) · revision(M) · tag(0.0.N-rM) · digest(same 일 때 옮길 이미지) · push_sha(커밋 태그가 아직 없는가)를 함께 낸다.

- 내용 키 태그(content-<키>-rM)가 있으면 same — 그 이미지의 버전 태그를 그대로 쓴다. 강제면 이 판정을 건너뛴다.
- jar 키 태그(jar-<키>-rM)가 있으면 revision — 그 이미지들의 N 에서 저장소의 0.0.N-r* 최대 M + 1.
- 둘 다 없으면 new — 저장소의 버전 태그 최대 N + 1, r1. 처음이면 0.0.1-r1.
- 버전 태그는 jar 의 build.version 과 같은 표기에 -rM 만 붙인다(v 를 붙이지 않는다).
- 태그는 불변이라 이미지마다 이름이 달라야 해서 jar · content 태그에도 -rM 을 단다.
- 번호는 정수로 비교한다. SemVer 로 정렬하면 0.0.1-r2 < 0.0.1(pre-release)이 된다.
"""
import json
import re
import sys

VERSION = re.compile(r"^0\.0\.(\d+)-r(\d+)$")
JAR = re.compile(r"^jar-([0-9a-f]{64})-r(\d+)$")
CONTENT = re.compile(r"^content-([0-9a-f]{64})-r(\d+)$")


def version_of(image, revision):
    """표지(jar · content 태그)의 rM 과 짝인 버전 태그의 N. 같은 이미지가 두 번 올라가 버전 태그가 여럿이어도 표지의 M 으로 고른다."""
    found = [VERSION.match(t) for t in image.get("imageTags", [])]
    found = [int(m[1]) for m in found if m and int(m[2]) == revision]
    if len(found) != 1:
        raise ValueError(f"r{revision} 표지와 짝인 버전 태그가 하나가 아니다: {image['imageDigest']} {image.get('imageTags')}")
    return found[0]


def tagged(images, pattern, key):
    """pattern 태그의 키가 key 인 이미지를 (M, 이미지) 로, M 이 큰 것부터."""
    hits = []
    for image in images:
        for t in image.get("imageTags", []):
            m = pattern.match(t)
            if m and m[1] == key:
                hits.append((int(m[2]), image))
    return sorted(hits, key=lambda h: h[0], reverse=True)


def decide(images, jar_key, content_key, sha, forced=False):
    push_sha = not any(sha in image.get("imageTags", []) for image in images)
    versions = [VERSION.match(t) for image in images for t in image.get("imageTags", [])]
    versions = [(int(m[1]), int(m[2])) for m in versions if m]

    same = [] if forced else tagged(images, CONTENT, content_key)
    if same:
        m, image = same[0]
        n = version_of(image, m)
        kind, digest = "same", image["imageDigest"]
    else:
        digest = None
        known = {version_of(image, r) for r, image in tagged(images, JAR, jar_key)}
        if len(known) > 1:
            raise ValueError(f"jar 키 하나에 버전이 여럿이다: {sorted(known)}")
        if known:
            n = known.pop()
            m = max(r for v, r in versions if v == n) + 1
            kind = "revision"
        else:
            n = max((v for v, _ in versions), default=0) + 1
            m = 1
            kind = "new"

    return {
        "kind": kind,
        "version": f"0.0.{n}",
        "revision": m,
        "tag": f"0.0.{n}-r{m}",
        "digest": digest,
        "push_sha": push_sha,
    }


if __name__ == "__main__":
    if len(sys.argv) != 6 or sys.argv[5] not in ("normal", "forced"):
        sys.exit("사용: release_version.py <describe-images.json> <jar 키> <내용 키> <커밋> <normal|forced>")
    with open(sys.argv[1]) as f:
        details = json.load(f)["imageDetails"]
    print(json.dumps(decide(details, sys.argv[2], sys.argv[3], sys.argv[4], sys.argv[5] == "forced")))
