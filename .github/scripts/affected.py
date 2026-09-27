#!/usr/bin/env python3
"""바뀐 파일 목록에서 다시 빌드하거나 테스트해야 할 대상을 고른다.

의존 관계는 여기에 적지 않는다. `./gradlew serviceGraph` 가 build.gradle 에서
뽑아낸 그래프를 읽을 뿐이다. 모듈을 추가하거나 의존을 바꿔도 이 파일은 그대로다.

사용:
    affected.py <graph.json> <changed-files.txt>              # 배포 서비스
    affected.py --modules <graph.json> <changed-files.txt>    # 테스트할 모듈

기본(release.yml): 서비스 이름의 JSON 배열. 예) ["member","catalog"]
--modules(build.yml): 모듈 경로의 JSON 배열. 예) [":common:web",":member"]

두 모드의 차이는 "무엇을 고르냐"뿐이다. 무시 목록과 소유자 판정은 공유한다.
어느 쪽이든 애매하면 전부 고른다 — 과잉 실행은 낭비로 끝나지만
누락은 깨진 코드를 통과시킨다.
"""

import fnmatch
import json
import sys

# 빌드 산출물에 영향이 없는 경로. 여기 걸리면 아무것도 빌드하지 않는다.
# 의존 관계가 아니라 "빌드와 무관한 파일" 목록이라 잘 변하지 않고,
# 틀려도 과잉 빌드로 끝난다 — 빌드를 거르는 방향으로는 틀리지 않는다.
IGNORED = [
    "docs/*",
    "*.md",
    ".gitignore",
    ".dockerignore",
    "LICENSE",
    ".github/ISSUE_TEMPLATE/*",
    ".github/*.md",
    ".claude/*",
]


def is_ignored(path: str) -> bool:
    return any(fnmatch.fnmatch(path, pat) for pat in IGNORED)


def owner_of(path: str, project_dirs: dict) -> str | None:
    """파일이 어느 모듈 소유인지 찾는다. 가장 깊게 맞는 모듈이 임자다.

    common/redis/src/Foo.java 는 `common` 과 `common/redis` 둘 다에 걸리는데,
    더 긴 쪽(:common:redis)이 실제 소유자다.
    """
    best, best_len = None, -1
    for project, directory in project_dirs.items():
        prefix = directory.rstrip("/") + "/"
        if path.startswith(prefix) and len(prefix) > best_len:
            best, best_len = project, len(prefix)
    return best


def main() -> int:
    args = sys.argv[1:]
    by_module = "--modules" in args
    args = [a for a in args if a != "--modules"]

    graph = json.load(open(args[0]))
    changed = [ln.strip() for ln in open(args[1]) if ln.strip()]

    project_dirs: dict = graph["projects"]
    # 서비스 모드는 배포 단위(services)를, 모듈 모드는 테스트 단위(closures)를 본다.
    # 자료 구조가 "이름 → 전이 의존 목록"으로 같아서 아래 판정은 하나로 쓴다.
    targets: dict = graph["closures"] if by_module else graph["services"]
    all_targets = sorted(targets)

    # 어느 대상도 의존하지 않는 모듈(예: src 없는 컨테이너 프로젝트)이 바뀌면
    # 판단할 근거가 없다. 그럴 땐 전부 고른다 — 거르는 것보다 안전하다.
    depended_on = {p for closure in targets.values() for p in closure}

    touched: set = set()
    global_change = False
    reasons: list = []

    for path in changed:
        if is_ignored(path):
            reasons.append(f"  - {path}  → 무시")
            continue
        owner = owner_of(path, project_dirs)
        if owner is None:
            # 루트 build.gradle, gradle wrapper, Dockerfile, 워크플로 등.
            # 어느 모듈에도 속하지 않으면 전부에 영향을 준다고 본다.
            global_change = True
            reasons.append(f"  - {path}  → 전역")
        elif owner not in depended_on:
            global_change = True
            reasons.append(f"  - {path}  → {owner} (의존하는 서비스 없음 · 전역 처리)")
        else:
            touched.add(owner)
            reasons.append(f"  - {path}  → {owner}")

    if global_change:
        picked = all_targets
    else:
        picked = sorted(
            name for name, closure in targets.items() if touched & set(closure)
        )

    print("변경 파일 분류:", file=sys.stderr)
    print("\n".join(reasons) or "  (없음)", file=sys.stderr)
    print(f"영향받은 모듈: {sorted(touched) or '없음'}", file=sys.stderr)
    print(f"전역 변경: {global_change}", file=sys.stderr)

    print(json.dumps(picked, separators=(",", ":")))
    return 0


if __name__ == "__main__":
    sys.exit(main())
