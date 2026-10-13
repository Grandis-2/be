#!/usr/bin/env python3
"""docker/otel/agent.properties 가 끈 OTel 계측을 다시 켜야 하는지 본다. 하나라도 걸리면 실패한다.

  python3 .github/scripts/otel_agent_config.py dist        (dist/<서비스>/app.jar — bootJar)

- 모양: 끄는 줄(otel.instrumentation.<이름>.enabled=false)마다 바로 위에 `# jar:` · `# src:` · `# 의도적:` 중 하나가 있다.
- jar: 서비스 이미지에 실릴 라이브러리(bootJar 의 BOOT-INF/lib)에 그 jar 가 있으면 실패. 선언이 아니라 실제로 실리는 것을 본다.
- src: main 소스(**/src/main)에 그 패턴이 있으면 실패.
- 이름: docker/Dockerfile 이 고정한 에이전트 이미지의 계측 모듈 이름에 없으면 실패(에이전트를 올려 이름이 바뀐 것).
  모듈 이름은 각 InstrumentationModule 생성자가 넘기는 문자열이다 — javap 로 읽는다(JDK 필요).
"""
import re
import subprocess
import sys
import tempfile
import zipfile
from pathlib import Path

ROOT = Path(__file__).resolve().parents[2]
CONF = "docker/otel/agent.properties"
PROPERTY = re.compile(r"^otel\.instrumentation\.([a-z0-9.-]+)\.enabled=false$")
META = re.compile(r"^# (jar|src|의도적): (.+)$")


def parse():
    entries, errors, previous = [], [], ""
    for number, line in enumerate((ROOT / CONF).read_text(encoding="utf-8").splitlines(), 1):
        line = line.rstrip()
        if not line or line.startswith("#"):
            if line:
                previous = line
            continue
        m = PROPERTY.match(line)
        if not m:
            errors.append(f"{CONF}:{number}: 계측을 끄는 줄(otel.instrumentation.<이름>.enabled=false)만 둔다 — {line}")
        else:
            meta = META.match(previous)
            if not meta:
                errors.append(f"{CONF}:{number}: {m.group(1)} 바로 위에 '# jar:' · '# src:' · '# 의도적:' 중 하나가 없다")
            else:
                entries.append((number, m.group(1), meta.group(1), meta.group(2)))
        previous = ""
    names = [e[1] for e in entries]
    errors += [f"{CONF}: {n} 가 두 번 있다" for n in sorted({n for n in names if names.count(n) > 1})]
    return entries, errors


def bundled_jars(dist):
    jars = {}
    for app in sorted(Path(dist).glob("*/app.jar")):
        with zipfile.ZipFile(app) as z:
            jars[app.parent.name] = sorted(Path(n).name for n in z.namelist()
                                           if n.startswith("BOOT-INF/lib/") and n.endswith(".jar"))
    return jars


def agent_module_names():
    image = re.search(r"^FROM (\S+) AS otel$", (ROOT / "docker/Dockerfile").read_text(), re.M).group(1)
    with tempfile.TemporaryDirectory() as tmp:
        container = subprocess.run(["docker", "create", image, "x"], check=True, capture_output=True, text=True).stdout.strip()
        try:
            subprocess.run(["docker", "cp", f"{container}:/javaagent.jar", f"{tmp}/javaagent.jar"], check=True, capture_output=True)
        finally:
            subprocess.run(["docker", "rm", container], capture_output=True)
        classes = []
        with zipfile.ZipFile(f"{tmp}/javaagent.jar") as z:
            for n in z.namelist():
                if n.startswith("inst/") and n.endswith("InstrumentationModule.classdata"):
                    target = Path(tmp, n[:-len(".classdata")] + ".class")
                    target.parent.mkdir(parents=True, exist_ok=True)
                    target.write_bytes(z.read(n))
                    classes.append(str(target))
        listing = subprocess.run(["javap", "-c", "-p", *classes], check=True, capture_output=True, text=True).stdout
    # 생성자(<클래스>();) 안의 문자열 상수가 계측 이름이다 — super(이름, 별칭…) 호출까지
    names, inside = set(), False
    for line in listing.splitlines():
        if re.match(r"\s+(public|protected) [\w.$]+InstrumentationModule\(\);", line):
            inside = True
        elif inside and "invokespecial" in line and "<init>" in line:
            inside = False
        elif inside:
            m = re.search(r"// String (\S+)$", line)
            if m:
                names.add(m.group(1))
    if len(names) < 100:
        sys.exit(f"에이전트 {image} 에서 계측 이름을 {len(names)}개밖에 못 읽었다 — 읽는 방식이 에이전트와 맞지 않는다")
    return image, names


def main():
    dist = sys.argv[1] if len(sys.argv) > 1 else "dist"
    entries, errors = parse()
    jars = bundled_jars(dist)
    if not jars:
        sys.exit(f"{dist}/<서비스>/app.jar 가 없다")

    for number, name, kind, value in entries:
        where = f"{CONF}:{number} {name}"
        if kind == "jar":
            pattern = re.compile(rf"^(?:{value})[-_.]")
            for service, libs in jars.items():
                for jar in libs:
                    if pattern.match(jar):
                        errors.append(f"{service}: {jar} 가 실린다 → {where} 를 지워 계측을 켠다")
        elif kind == "src":
            found = subprocess.run(["git", "grep", "-nE", value, "--", ":(glob)**/src/main/**"],
                                   cwd=ROOT, capture_output=True, text=True)
            if found.returncode not in (0, 1):
                errors.append(f"{where}: git grep 실패 — {found.stderr.strip()}")
            for hit in found.stdout.splitlines():
                errors.append(f"{hit.split(':', 2)[0]}:{hit.split(':', 2)[1]}: {value} 를 쓴다 → {where} 를 지워 계측을 켠다")

    image, names = agent_module_names()
    for number, name, _, _ in entries:
        if name not in names:
            errors.append(f"{CONF}:{number} {name}: 에이전트 {image} 에 없는 계측 이름이다 — 바뀐 이름으로 고친다")

    print(f"끈 계측 {len(entries)}개 · 서비스 {len(jars)}개 · 에이전트 계측 이름 {len(names)}개")
    if errors:
        print("\n".join(errors))
        sys.exit(1)


if __name__ == "__main__":
    main()
