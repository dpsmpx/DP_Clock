#!/usr/bin/env python3
"""Вызовы Java из :core:model, которых нет в Android minSdk (сборка 0.9.0).

    tools/check-core-api.py [minSdk]

Lint проверяет API Android только в модуле :app, а код :core:model идёт в
тот же APK. Скрипт берёт скомпилированные классы модели (javap), находит
вызовы классов java.* и javax.* и сверяет их с базой API Android SDK
(platforms/*/data/api-versions.xml): с какой версии Android метод есть.
Сначала нужна сборка: ./gradlew :core:model:classes. Код выхода 1 — есть
вызовы новее minSdk.
"""
import glob
import os
import re
import subprocess
import sys
import xml.etree.ElementTree as ET

ROOT = os.path.dirname(os.path.dirname(os.path.abspath(__file__)))
MIN_SDK = int(sys.argv[1]) if len(sys.argv) > 1 else 31
CLASSES = os.path.join(ROOT, "core/model/build/classes/kotlin/main")


def sdk_dir():
    for line in open(os.path.join(ROOT, "local.properties"), encoding="utf-8"):
        if line.startswith("sdk.dir="):
            return line.split("=", 1)[1].strip()
    return os.environ.get("ANDROID_HOME", "")


def level(text):
    """Версия API: «36» или «36.1» (минорные выпуски) — по старшему числу."""
    return int(float(text))


def api_database():
    files = sorted(glob.glob(os.path.join(sdk_dir(), "platforms/*/data/api-versions.xml")))
    if not files:
        sys.exit("✗ нет api-versions.xml в Android SDK")
    since = {}
    parents = {}
    for cls in ET.parse(files[-1]).getroot().iter("class"):
        name = cls.get("name")
        class_since = level(cls.get("since", "1"))
        since[name] = class_since
        parents[name] = [p.get("name") for p in cls if p.tag in ("extends", "implements")]
        for method in cls.iter("method"):
            since[name + "#" + method.get("name")] = level(method.get("since", str(class_since)))
    return since, parents


def method_since(since, parents, cls, method):
    """Метод ищется в классе и выше — в предках и интерфейсах: equals у String объявлен в Object."""
    seen = set()
    queue = [cls]
    while queue:
        current = queue.pop(0)
        if current in seen:
            continue
        seen.add(current)
        api = since.get(current + "#" + method)
        if api is not None:
            return max(api, since.get(cls, 1))
        queue += parents.get(current, []) or (["java/lang/Object"] if current != "java/lang/Object" else [])
    return None


def references():
    files = glob.glob(os.path.join(CLASSES, "**/*.class"), recursive=True)
    if not files:
        sys.exit("✗ нет классов: сначала ./gradlew :core:model:classes")
    out = subprocess.run(["javap", "-c", "-p", *files], capture_output=True, text=True, check=True).stdout
    found = {}
    current = None
    for line in out.splitlines():
        header = re.match(r"(?:public |final |abstract |private |protected )*(?:class|interface|enum) (\S+)", line.strip())
        if header and not line.startswith(" "):
            current = header.group(1)
        match = re.search(r"// (?:Interface)?Method ((?:java|javax)/[\w/$]+)\.(\"?[\w<>$]+\"?):(\([^ ]*)", line)
        if match:
            cls, method, signature = match.group(1), match.group(2).strip('"'), match.group(3)
            found.setdefault((cls, method, signature), set()).add(current)
    return found


def main():
    since, parents = api_database()
    problems = []
    for (cls, method, signature), users in sorted(references().items()):
        api = method_since(since, parents, cls, method + signature)
        if api is None:
            # В базе нет такой перегрузки — значит, её нет в Android совсем.
            problems.append((cls, method, signature, "нет в Android", users))
        elif api > MIN_SDK:
            problems.append((cls, method, signature, f"с API {api}", users))
    for cls, method, signature, why, users in problems:
        print(f"✗ {cls.replace('/', '.')}.{method}{signature} — {why}; в {', '.join(sorted(users))}")
    if problems:
        sys.exit(1)
    print(f"✓ вызовы java.* в :core:model есть в Android {MIN_SDK}+")


main()
