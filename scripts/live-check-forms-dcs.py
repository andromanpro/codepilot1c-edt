# -*- coding: utf-8 -*-
"""Живая проверка фиксов форм (Gitea androman/codepilot1c#109) и СКД (#107) на установленной сборке.

Зачем. Юнит-тесты, eval и сборка не проверяют рантайм-контракт MCP-хоста и то, что EDT реально
сериализует в Form.form / Template.dcs (урок порта на 1.3.9: регрессия вылезла только живым
вызовом тула). Скрипт гоняет мутирующие тулы через MCP по HTTP на ОДНОРАЗОВОМ проекте и сверяет
результат по файлам на диске, а не по слову «successfully» в ответе.

Создаёт объекты с уникальным суффиксом (документ с формой, отчёт со схемой, HTTP-сервис, обработка
с макетами), чужие объекты и стенды не трогает. Проект должен быть уже импортирован в workspace EDT
(или --import).

Разделы: формы, СКД, политика подтверждений хоста для тулов без validation token (#85, после порта
тег-линии upstream — #115), маршруты HTTP-сервиса по строгому контракту, файлы содержимого макетов по
типу (#121). Любой отключается --skip-*.

    py -3.14 scripts/live-check-forms-dcs.py --project CpScratchForms --project-dir <каталог проекта>
        [--import] [--xdto-check <путь к dcs_xdto_check.py>] [--workspace-dir <каталог workspace EDT>]

🚨 Во время прогона другой клиент codepilot не должен работать: HTTP-очередь хоста общая, и
параллельный вызов может получить чужой ответ. Запросы здесь с уникальными id, ответ сверяется
с id запроса.

Код выхода: 0 — все проверки зелёные, 1 — есть красные, 2 — окружение (нет сервера, токена).
"""
import argparse
import datetime
import json
import os
import re
import subprocess
import sys
import time
import urllib.error
import urllib.request
import xml.etree.ElementTree as ET

sys.stdout.reconfigure(encoding="utf-8")
sys.stderr.reconfigure(encoding="utf-8")

URL = "http://127.0.0.1:8766/mcp"
XSI_TYPE = "{http://www.w3.org/2001/XMLSchema-instance}type"
DCS_NS = "{http://v8.1c.ru/8.1/data-composition-system/schema}"
STANDARD_COMMANDS_FROM_THE_REPORT = ["Copy", "Delete", "SetDeletionMark", "Post", "PostAndClose",
                                     "UndoPosting", "Write", "WriteAndClose"]


# ─── MCP по HTTP ────────────────────────────────────────────────────────────

class Mcp:
    def __init__(self, url):
        self.url = url
        self.auth = self._auth()
        self.session = None
        self.counter = 1000

    @staticmethod
    def _auth():
        with open(os.path.join(os.path.expanduser("~"), ".claude.json"), encoding="utf-8") as fh:
            cfg = json.load(fh)
        value = cfg.get("mcpServers", {}).get("codepilot1c", {}).get("headers", {}).get("Authorization")
        if not value:
            raise SystemExit("ОКРУЖЕНИЕ: нет Authorization для codepilot1c в ~/.claude.json")
        return value

    def _post(self, body, timeout):
        headers = {"Content-Type": "application/json", "Accept": "application/json, text/event-stream",
                   "Authorization": self.auth}
        if self.session:
            headers["Mcp-Session-Id"] = self.session
        request = urllib.request.Request(self.url, data=json.dumps(body, ensure_ascii=False).encode("utf-8"),
                                         headers=headers, method="POST")
        with urllib.request.urlopen(request, timeout=timeout) as response:
            return response.read().decode("utf-8", errors="replace"), response.headers.get("Mcp-Session-Id")

    def open(self):
        _, session = self._post({"jsonrpc": "2.0", "id": 1, "method": "initialize",
                                 "params": {"protocolVersion": "2025-06-18", "capabilities": {},
                                            "clientInfo": {"name": "live-check-forms-dcs", "version": "1"}}}, 60)
        if not session:
            raise SystemExit("ОКРУЖЕНИЕ: codepilot не выдал Mcp-Session-Id")
        self.session = session
        self._post({"jsonrpc": "2.0", "method": "notifications/initialized"}, 60)

    def call(self, tool, arguments, timeout=900):
        self.counter += 1
        request_id = self.counter
        raw, _ = self._post({"jsonrpc": "2.0", "id": request_id, "method": "tools/call",
                             "params": {"name": tool, "arguments": arguments}}, timeout)
        candidates = [line[5:].strip() for line in raw.splitlines() if line.startswith("data:")] or [raw]
        for chunk in candidates:
            try:
                parsed = json.loads(chunk)
            except ValueError:
                continue
            if isinstance(parsed, dict) and parsed.get("id") == request_id:
                if "error" in parsed:
                    return True, json.dumps(parsed["error"], ensure_ascii=False)
                result = parsed.get("result") or {}
                text = "\n".join(c.get("text", "") for c in result.get("content") or [] if c.get("type") == "text")
                return bool(result.get("isError")), text
        raise SystemExit(f"ответ не относится к запросу {tool} (id {request_id}) — подмена, прогон недостоверен")


# ─── проверки ───────────────────────────────────────────────────────────────

class Report:
    def __init__(self):
        self.failed = 0
        self.passed = 0

    def check(self, label, ok, detail=""):
        if ok:
            self.passed += 1
            print(f"  ✅ {label}")
        else:
            self.failed += 1
            print(f"  ❌ {label}" + (f" — {detail}" if detail else ""))
        return ok


def mutate(mcp, project, tool, payload, retries=12):
    """validate → apply back-to-back; PROJECT_NOT_READY повторяется с паузой."""
    for attempt in range(retries):
        is_error, text = mcp.call("edt_validate_request", {"project": project, "operation": tool, "payload": payload})
        if is_error:
            if "PROJECT_NOT_READY" in text and attempt + 1 < retries:
                time.sleep(10)
                continue
            return True, "validate: " + text
        token = json.loads(text).get("validationToken")
        is_error, text = mcp.call(tool, dict(payload, validation_token=token))
        if is_error and "PROJECT_NOT_READY" in text and attempt + 1 < retries:
            time.sleep(10)
            continue
        return is_error, text
    return True, "PROJECT_NOT_READY после всех попыток"


def form_ops(mcp, project, form_fqn, operations):
    return mutate(mcp, project, "mutate_form_model",
                  {"project": project, "form_fqn": form_fqn, "operations": operations})


def parse_form(path):
    return ET.parse(path).getroot()


def all_items(element):
    """Все элементы формы с именем: items, autoCommandBar, contextMenu — вместе с контейнером-родителем."""
    for child in element:
        name = child.find("name")
        if name is not None and child.tag in ("items", "autoCommandBar", "contextMenu", "extendedTooltip"):
            yield element, child
        yield from all_items(child)


def find_item(root, name):
    for parent, item in all_items(root):
        if item.find("name").text == name:
            return parent, item
    return None, None


def button_kind(item):
    kind = item.find("type")
    # EMF не пишет значение по умолчанию: у кнопки это CommandBarButton.
    return "CommandBarButton" if kind is None else kind.text


def find_button_by_command(root, command):
    """Кнопка ищется по команде, а не по имени: имя — предмет отдельной проверки."""
    for parent in root.iter():
        for child in parent:
            if child.get(XSI_TYPE) == "form:Button" and child.findtext("commandName") == "Form.Command." + command:
                return parent, child
    return None, None


def inspect_nodes(mcp, project, form_fqn):
    is_error, text = mcp.call("inspect_form_layout", {"project": project, "form_fqn": form_fqn,
                                                      "include_invisible": True, "include_properties": False})
    if is_error:
        raise SystemExit("inspect_form_layout: " + text)
    data = json.loads(text)
    # Gson пишет дерево ключом items (компонент записи InspectFormLayoutResult); nodes — на случай переименования.
    nodes = data.get("items") or data.get("nodes")
    if nodes is None:
        raise SystemExit("inspect_form_layout: в ответе нет дерева элементов, ключи: " + ", ".join(data))
    return nodes


def walk_nodes(nodes, parent=None):
    for node in nodes:
        yield parent, node
        yield from walk_nodes(node.get("children") or [], node)


def diagnostics_errors(mcp, workspace_path):
    is_error, text = mcp.call("get_diagnostics", {"scope": "file", "path": workspace_path,
                                                  "severity": "warning", "wait_ms": 2000})
    if is_error:
        return None, text
    count = re.search(r"Итого:\**\s*(\d+)\s+ошиб", text)
    return (int(count.group(1)) if count else None), text


# ─── сценарий ───────────────────────────────────────────────────────────────

def check_forms(mcp, report, project, project_dir, suffix, reuse=False):
    doc = "ПроверкаФорм" + suffix
    form_fqn = f"Document.{doc}.Form.ФормаДокумента"
    form_dir = os.path.join(project_dir, "src", "Documents", doc, "Forms", "ФормаДокумента")
    form_file = os.path.join(form_dir, "Form.form")
    module_file = os.path.join(form_dir, "Module.bsl")
    print(f"\n== Формы: {form_fqn}" + (" (объекты прошлого прогона)" if reuse else ""))

    # --reuse-suffix: документ и форма уже созданы прошлым прогоном. Нужен, пока create_form на
    # объекте, созданном в той же сессии EDT, ложно отвечает FORM_MATERIALIZATION_TIMEOUT
    # (форма при этом создаётся) — Gitea androman/codepilot1c#112; исправлено в той же волне.
    for tool, payload in [] if reuse else [
        ("create_metadata", {"project": project, "kind": "Document", "name": doc, "synonym": "Проверка форм"}),
        ("add_metadata_child", {"project": project, "parent_fqn": f"Document.{doc}", "child_kind": "Attribute",
                                "name": "Комментарий", "properties": {"type": "String(100)"}}),
        ("add_metadata_child", {"project": project, "parent_fqn": f"Document.{doc}", "child_kind": "Tabular_Section",
                                "name": "Товары"}),
        ("add_metadata_child", {"project": project, "parent_fqn": f"Document.{doc}.TabularSection.Товары",
                                "child_kind": "Attribute", "name": "Наименование", "properties": {"type": "String(50)"}}),
        ("create_form", {"project": project, "owner_fqn": f"Document.{doc}", "name": "ФормаДокумента",
                         "usage": "OBJECT", "set_as_default": True}),
    ]:
        is_error, text = mutate(mcp, project, tool, payload)
        if tool == "create_form" and is_error and "FORM_MATERIALIZATION_TIMEOUT" in text and os.path.exists(form_file):
            # Форма создана, но сторож материализации искал .mdo владельца не там: для объекта,
            # созданного в той же сессии EDT, URI не файловый. Отдельная проверка, прогон продолжается.
            report.check("create_form отвечает успехом для владельца, созданного в этой сессии", False, text[:300])
            continue
        if not report.check(f"подготовка: {tool} {payload.get('name', '')}", not is_error, text[:300]):
            return

    # 3. автокомандная панель видна в inspect
    nodes = inspect_nodes(mcp, project, form_fqn)
    bar = next((node for _, node in walk_nodes(nodes) if node.get("name") == "ФормаКоманднаяПанель"), None)
    report.check("inspect_form_layout показывает ФормаКоманднаяПанель (id -1, AutoCommandBar)",
                 bar is not None and bar.get("id") == -1 and str(bar.get("kind", "")).startswith("AutoCommandBar"),
                 json.dumps(bar, ensure_ascii=False)[:200] if bar else "узла нет")
    table = next((node for _, node in walk_nodes(nodes) if node.get("name") == "Товары"), None)
    if not report.check("на форме есть таблица Товары", table is not None and "Table" in str(table.get("kind"))):
        return

    # 1. excludedCommands формы — список из исходного замера. У проводимого документа нет WriteAndClose
    #    (есть PostAndClose): тул обязан отказать со списком доступных, а не записать висячую ссылку.
    requested = list(STANDARD_COMMANDS_FROM_THE_REPORT)
    is_error, text = form_ops(mcp, project, form_fqn, [
        {"op": "set_form_props", "set": {"excludedCommands": requested}}])
    if is_error and "Available:" in text:
        available = {name.split(" (")[0].strip() for name in text.split("Available:", 1)[1].split(". Nothing")[0].split(", ")}
        report.check("список замера: недоступные форме команды отклонены со списком доступных, форма не тронута",
                     not parse_form(form_file).findall("excludedCommands"), text[:300])
        requested = [name for name in requested if name in available]
        is_error, text = form_ops(mcp, project, form_fqn, [
            {"op": "set_form_props", "set": {"excludedCommands": requested}}])
    report.check(f"set_form_props excludedCommands {requested} принят", not is_error, text[:400])
    root = parse_form(form_file)
    excluded = sorted(e.text for e in root.findall("excludedCommands"))
    report.check(f"Form.form: у формы исключены ровно {len(requested)} стандартных команд",
                 excluded == sorted(requested), str(excluded))

    # 1. excludedCommands таблицы: берём имена, которые у таблицы действительно есть
    wanted = ["Delete", "Copy"]
    is_error, text = form_ops(mcp, project, form_fqn, [
        {"op": "set_item", "item_name": "Товары", "set": {"excludedCommands": wanted}}])
    if is_error and "Available:" in text:
        wanted = [name.split(" (")[0].strip() for name in text.split("Available:", 1)[1].split(". Nothing")[0].split(", ")][:2]
        is_error, text = form_ops(mcp, project, form_fqn, [
            {"op": "set_item", "item_name": "Товары", "set": {"excludedCommands": wanted}}])
    report.check(f"set_item excludedCommands таблицы {wanted} принят", not is_error, text[:400])
    _, table_item = find_item(parse_form(form_file), "Товары")
    table_excluded = sorted(e.text for e in table_item.findall("excludedCommands")) if table_item is not None else []
    report.check("Form.form: исключения записаны у таблицы, исключения формы не тронуты",
                 table_excluded == sorted(wanted) and sorted(e.text for e in parse_form(form_file).findall("excludedCommands"))
                 == sorted(requested), f"таблица {table_excluded}")

    # 1. негатив: неизвестная команда отбивается со списком, ничего не меняя
    is_error, text = form_ops(mcp, project, form_fqn, [
        {"op": "set_form_props", "set": {"excludedCommands": ["НетТакойКоманды"]}}])
    report.check("неизвестная стандартная команда отклонена со списком доступных",
                 is_error and "Available:" in text and "НетТакойКоманды" in text, text[:300])

    # 2. titleDataPath страницы + перенос таблицы на страницу
    is_error, text = form_ops(mcp, project, form_fqn, [
        {"op": "add_group", "name": "ГруппаСтраницы", "group_type": "PAGES"},
        {"op": "add_group", "name": "СтраницаТовары", "group_type": "PAGE", "title": "Товары",
         "parent_item_name": "ГруппаСтраницы"},
        {"op": "move_item", "item_name": "Товары", "parent_item_name": "СтраницаТовары"},
        {"op": "set_item", "item_name": "СтраницаТовары", "set": {"titleDataPath": "Объект.Товары.RowsCount"}},
    ])
    report.check("страницы + move_item таблицы + titleDataPath приняты одним пакетом", not is_error, text[:400])
    parent, page = find_item(parse_form(form_file), "СтраницаТовары")
    segments = page.findall("./extInfo/titleDataPath/segments") if page is not None else []
    report.check("Form.form: titleDataPath страницы = Объект.Товары.RowsCount",
                 ".".join(s.text for s in segments) == "Объект.Товары.RowsCount",
                 ET.tostring(page, encoding="unicode")[:300] if page is not None else "страницы нет")
    table_parent, _ = find_item(parse_form(form_file), "Товары")
    report.check("Form.form: таблица перенесена на страницу",
                 table_parent is not None and table_parent.findtext("name") == "СтраницаТовары")

    # 3. add_button без родителя → автопанель; кнопка в обычной группе → обычная; перенос в панель
    is_error, text = form_ops(mcp, project, form_fqn, [
        {"op": "add_command", "name": "Обновить", "action": "Обновить", "title": "Обновить"},
        {"op": "add_button", "name": "КнопкаОбновить", "command_name": "Обновить"},
        {"op": "add_group", "name": "ГруппаКнопок", "group_type": "USUAL_GROUP"},
        {"op": "add_command", "name": "Пересчитать", "action": "Пересчитать", "title": "Пересчитать"},
        {"op": "add_button", "name": "КнопкаПересчитать", "command_name": "Пересчитать",
         "parent_item_name": "ГруппаКнопок"},
    ])
    report.check("команды и кнопки добавлены", not is_error, text[:400])
    root = parse_form(form_file)
    parent, refresh = find_button_by_command(root, "Обновить")
    report.check("Form.form: add_button без родителя — в autoCommandBar формы, тип CommandBarButton",
                 parent is not None and parent.tag == "autoCommandBar" and parent.findtext("name") == "ФормаКоманднаяПанель"
                 and button_kind(refresh) == "CommandBarButton",
                 f"родитель {parent.tag if parent is not None else None}, тип {button_kind(refresh) if refresh is not None else None}")
    parent, recalc = find_button_by_command(root, "Пересчитать")
    report.check("Form.form: кнопка в обычной группе — UsualButton",
                 parent is not None and parent.findtext("name") == "ГруппаКнопок" and button_kind(recalc) == "UsualButton",
                 f"тип {button_kind(recalc) if recalc is not None else None}")
    actual_names = [b.findtext("name") if b is not None else None for b in (refresh, recalc)]
    report.check("add_button: кнопки названы запрошенными именами (КнопкаОбновить, КнопкаПересчитать)",
                 actual_names == ["КнопкаОбновить", "КнопкаПересчитать"], f"фактические имена {actual_names}")
    recalc_name = actual_names[1] or "КнопкаПересчитать"

    is_error, text = form_ops(mcp, project, form_fqn, [
        {"op": "move_item", "item_name": recalc_name, "parent_item_name": "ФормаКоманднаяПанель"}])
    report.check("move_item в ФормаКоманднаяПанель принят (раньше: not a container)", not is_error, text[:400])
    parent, recalc = find_button_by_command(parse_form(form_file), "Пересчитать")
    report.check("Form.form: перенесённая кнопка в autoCommandBar и стала CommandBarButton (без SU107)",
                 parent is not None and parent.tag == "autoCommandBar" and button_kind(recalc) == "CommandBarButton",
                 f"родитель {parent.tag if parent is not None else None}, тип {button_kind(recalc) if recalc is not None else None}")
    nodes = inspect_nodes(mcp, project, form_fqn)
    bar = next((node for _, node in walk_nodes(nodes) if node.get("name") == "ФормаКоманднаяПанель"), None)
    children = sorted(child.get("commandRef") or child.get("name") for child in (bar or {}).get("children") or [])
    report.check("inspect_form_layout: обе кнопки (по командам) внутри узла ФормаКоманднаяПанель",
                 children == ["Обновить", "Пересчитать"], str(children))

    is_error, text = form_ops(mcp, project, form_fqn, [{"op": "remove_item", "item_name": "ФормаКоманднаяПанель"}])
    report.check("remove_item самой панели отклонён (служебная часть)", is_error and "structural part" in text, text[:300])

    # 4. заготовки серверных событий
    is_error, text = form_ops(mcp, project, form_fqn, [
        {"op": "add_event_handler", "target": "form", "event": "OnCreateAtServer", "handler_name": "ПриСозданииНаСервере"},
        {"op": "add_event_handler", "target": "form", "event": "OnReadAtServer", "handler_name": "ПриЧтенииНаСервере"},
        {"op": "add_event_handler", "target": "form", "event": "OnOpen", "handler_name": "ПриОткрытии"},
    ])
    report.check("add_event_handler OnCreateAtServer/OnReadAtServer/OnOpen принят", not is_error, text[:400])
    module = open(module_file, encoding="utf-8-sig").read() if os.path.exists(module_file) else ""
    for directive, procedure in [("&НаСервере", "ПриСозданииНаСервере"), ("&НаСервере", "ПриЧтенииНаСервере"),
                                 ("&НаКлиенте", "ПриОткрытии")]:
        found = re.search(re.escape(directive) + r"[ \t]*\r?\n[ \t]*Процедура " + procedure + r"\(", module)
        report.check(f"Module.bsl: {directive} перед Процедура {procedure}", found is not None,
                     re.search(r".*\r?\n[ \t]*Процедура " + procedure + r"\(.*", module).group(0)
                     if re.search(r"Процедура " + procedure + r"\(", module) else "процедуры нет")

    rel = os.path.relpath(form_dir, os.path.dirname(project_dir)).replace(os.sep, "/")
    for name in ("Form.form", "Module.bsl"):
        errors, text = diagnostics_errors(mcp, f"{rel}/{name}")
        report.check(f"диагностика EDT {name}: 0 ошибок, нет SU107", errors == 0 and "SU107" not in text, text[:400])


def check_dcs(mcp, report, project, project_dir, suffix, xdto_script):
    owner = "Report.ПроверкаСКД" + suffix
    print(f"\n== СКД: {owner}")
    steps = [
        ("create_metadata", {"project": project, "kind": "Report", "name": owner.split(".")[1], "synonym": "Проверка СКД"}),
        ("dcs_manage", {"command": "create_schema", "project": project, "owner_fqn": owner}),
        ("dcs_manage", {"command": "upsert_dataset", "project": project, "owner_fqn": owner, "dataset_name": "НаборДанных1",
                        "query": "ВЫБРАТЬ 1 КАК Поле", "data_source": "ИсточникДанных1",
                        "auto_fill_available_fields": False, "use_query_group_if_possible": False}),
        ("dcs_manage", {"command": "upsert_dataset", "project": project, "owner_fqn": owner, "dataset_name": "НаборДанных2",
                        "query": "ВЫБРАТЬ 2 КАК Поле", "data_source": "ВторойИсточник"}),
        ("dcs_manage", {"command": "upsert_param", "project": project, "owner_fqn": owner, "parameter_name": "Период",
                        "expression": "ТекущаяДата()", "use_restriction": False, "available_as_field": False,
                        "value_list_allowed": True}),
        ("dcs_manage", {"command": "upsert_field", "project": project, "owner_fqn": owner, "data_path": "Удвоенное",
                        "expression": "Поле * 2"}),
        ("dcs_manage", {"command": "upsert_dataset", "project": project, "owner_fqn": owner, "dataset_name": "НаборДанных2",
                        "query": "ВЫБРАТЬ 3 КАК Поле"}),
    ]
    last = ""
    for tool, payload in steps:
        is_error, text = mutate(mcp, project, tool, payload)
        if not report.check(f"{tool} {payload.get('command', '')} {payload.get('dataset_name', payload.get('parameter_name', payload.get('data_path', '')))}",
                            not is_error, text[:300]):
            return
        last = text
    report.check("upsert без data_source сохранил источник набора (ВторойИсточник)", "ВторойИсточник" in last, last[:300])

    templates = os.path.join(project_dir, "src", "Reports", owner.split(".")[1], "Templates")
    schema_files = [os.path.join(dirpath, f) for dirpath, _, files in os.walk(templates) for f in files if f == "Template.dcs"]
    if not report.check("Template.dcs создан", len(schema_files) == 1, str(schema_files)):
        return
    root = ET.parse(schema_files[0]).getroot()
    top = [child.tag.replace(DCS_NS, "") for child in root]
    report.check("верх схемы: dataSource×2, dataSet×2, calculatedField, parameter",
                 top == ["dataSource", "dataSource", "dataSet", "dataSet", "calculatedField", "parameter"], str(top))
    sets = {ds.findtext(DCS_NS + "name"): ds for ds in root.findall(DCS_NS + "dataSet")}
    first = [child.tag.replace(DCS_NS, "") for child in sets.get("НаборДанных1", [])]
    report.check("НаборДанных1: name, dataSource, query, autoFillFields, useQueryGroupIfPossible",
                 first == ["name", "dataSource", "query", "autoFillFields", "useQueryGroupIfPossible"], str(first))
    second = sets.get("НаборДанных2")
    report.check("НаборДанных2 привязан к ВторойИсточник и после повторного upsert",
                 second is not None and second.findtext(DCS_NS + "dataSource") == "ВторойИсточник")
    parameter = [child.tag.replace(DCS_NS, "") for child in root.find(DCS_NS + "parameter")]
    report.check("параметр: useRestriction раньше expression, valueListAllowed раньше availableAsField",
                 parameter == ["name", "useRestriction", "expression", "valueListAllowed", "availableAsField"], str(parameter))
    report.check("в схеме нет autoFillAvailableFields", b"autoFillAvailableFields" not in open(schema_files[0], "rb").read())
    if xdto_script:
        run = subprocess.run([sys.executable, xdto_script, schema_files[0]], capture_output=True, text=True,
                             encoding="utf-8", timeout=900)
        report.check("платформенная XDTO читает Template.dcs", run.returncode == 0, (run.stdout + run.stderr)[-400:])


def host_mode(workspace_dir):
    """(профиль, политика мутаций) из настроек EDT; None — настройки не читались."""
    if not workspace_dir:
        return None
    prefs = os.path.join(workspace_dir, ".metadata", ".plugins", "org.eclipse.core.runtime", ".settings",
                         "com.codepilot1c.core.prefs")
    values = {}
    if os.path.exists(prefs):
        for line in open(prefs, encoding="utf-8", errors="replace"):
            key, _, value = line.strip().partition("=")
            values[key] = value
    # Значения по умолчанию — из McpHostConfig.defaults(): пустой профиль и ALLOW.
    return (values.get("mcp.host.policy.sessionProfile", ""),
            values.get("mcp.host.policy.defaultMutationDecision", "ALLOW"))


# Три формы отказа хоста (McpHostRequestRouter): профильный гейт и отказ неподтверждаемому тулу —
# PermissionDenialPayload, политика мутаций — текст toolError, фильтр экспозиции — свой текст.
REFUSAL_MARKERS = ("запрещен политикой", "reason_code=", "permission_denied",
                   "denied by permission policy", "Tool is not exposed")
REFUSAL_SAMPLE = ("Инструмент запрещен политикой профиля: write_file (profile=, "
                  "reason_code=confirmation_unavailable_tool_policy, layer=tool)")


def refused(text):
    return any(marker in text for marker in REFUSAL_MARKERS)


def check_host_policy(mcp, report, project, project_dir, suffix, workspace_dir):
    """Тулы, требующие подтверждения, но без validation token, в legacy-режиме хоста.

    Токенные тулы (create_metadata, mutate_form_model, dcs_manage) проходят хост и по обходу upstream
    для validation token, поэтому их зелёный ответ правило форка не доказывает. Без профиля такие тулы
    пропускает только McpHostConfirmationPolicy (Gitea androman/codepilot1c#85): потеряй её вызов в
    McpHostRequestRouter при синке — и уже эти проверки получат confirmation_unavailable_tool_policy,
    хотя карта дельты такую потерю не видит.
    """
    print("\n== Политика подтверждений MCP-хоста: тулы без validation token")
    mode = host_mode(workspace_dir)
    if mode is None:
        print("  ⚠ режим хоста не сверен (нет --workspace-dir): проверки ниже верны только без профиля и при ALLOW")
    elif mode != ("", "ALLOW"):
        print(f"  ⚠ пропущено: хост не в legacy-режиме с ALLOW (профиль «{mode[0]}», политика {mode[1]})")
        return
    else:
        print("  режим хоста: профиль пустой, defaultMutationDecision=ALLOW")

    # Детектор, не узнающий настоящий текст отказа, делает обе проверки ниже вечнозелёными.
    if not report.check("детектор узнаёт текст отказа хоста", refused(REFUSAL_SAMPLE)):
        return

    is_error, text = mcp.call("edt_diagnostics", {"command": "uuid_check", "project": project})
    report.check("edt_diagnostics uuid_check выполнен, а не отбит хостом",
                 not refused(text) and "Значений uuid-атрибутов" in text, text[:300])
    report.check("uuid_check: дублей и пустых uuid нет", not is_error, text[:300])

    name = f"ПроверкаПолитики{suffix}.md"
    content = f"Проверка политики подтверждений MCP-хоста, прогон {suffix}.\n"
    is_error, text = mcp.call("write_file", {"path": f"{project}/{name}", "content": content, "overwrite": True})
    report.check("write_file без токена не отбит хостом", not is_error and not refused(text), text[:300])
    target = os.path.join(project_dir, name)
    written = open(target, encoding="utf-8-sig").read() if os.path.exists(target) else None
    report.check("write_file: файл записан на диск тем содержимым", written == content, repr(written)[:200])
    if written is not None:
        os.remove(target)


def check_http(mcp, report, project, project_dir, suffix):
    """Маршруты HTTP-сервиса по строгому контракту upstream: без полей — отказ ещё на validate."""
    service = "ПроверкаHTTP" + suffix
    service_fqn = f"HTTPService.{service}"
    template_fqn = f"{service_fqn}.URLTemplate.Состояние"
    mdo_file = os.path.join(project_dir, "src", "HTTPServices", service, service + ".mdo")
    print(f"\n== HTTP-сервис: {service_fqn}")

    is_error, text = mutate(mcp, project, "create_metadata",
                            {"project": project, "kind": "HTTPService", "name": service, "synonym": "Проверка маршрутов"})
    if not report.check("подготовка: create_metadata HTTPService", not is_error, text[:300]):
        return
    child = {"project": project, "parent_fqn": service_fqn, "child_kind": "URLTemplate"}

    is_error, text = mutate(mcp, project, "add_metadata_child", dict(child, name="БезПути"))
    report.check("URLTemplate без template отклонён на validate", is_error and text.startswith("validate:")
                 and "template" in text, text[:300])
    is_error, text = mutate(mcp, project, "add_metadata_child", dict(child, name="Состояние", template="/state"))
    report.check("URLTemplate: template параметром верхнего уровня принят", not is_error, text[:300])

    method = {"project": project, "parent_fqn": template_fqn}
    is_error, text = mutate(mcp, project, "add_metadata_child",
                            dict(method, child_kind="HTTPMethod", name="БезОбработчика", http_method="POST"))
    report.check("HTTPMethod без handler отклонён на validate", is_error and text.startswith("validate:")
                 and "handler" in text, text[:300])
    is_error, text = mutate(mcp, project, "add_metadata_child",
                            dict(method, child_kind="Method", name="Отправить",
                                 properties={"http_method": "post", "handler": "СостояниеОтправить"}))
    report.check("Method (алиас форка): http_method и handler в properties приняты", not is_error, text[:300])

    if not report.check(".mdo HTTP-сервиса на диске", os.path.exists(mdo_file), mdo_file):
        return
    root = ET.parse(mdo_file).getroot()
    templates = {t.findtext("name"): t for t in root.findall("urlTemplates")}
    report.check(".mdo: из шаблонов создан только Состояние", sorted(templates) == ["Состояние"], str(sorted(templates)))
    state = templates.get("Состояние")
    report.check(".mdo: template = /state", state is not None and state.findtext("template") == "/state")
    methods = {m.findtext("name"): m for m in state.findall("methods")} if state is not None else {}
    report.check(".mdo: из методов создан только Отправить", sorted(methods) == ["Отправить"], str(sorted(methods)))
    sent = methods.get("Отправить")
    report.check(".mdo: httpMethod = POST (нормализован из post), handler = СостояниеОтправить",
                 sent is not None and sent.findtext("httpMethod") == "POST"
                 and sent.findtext("handler") == "СостояниеОтправить",
                 ET.tostring(sent, encoding="unicode")[:300] if sent is not None else "метода нет")


def template_files(project_dir, processor, template):
    """Файлы в Templates/<макет>/ обработки: имя → размер."""
    folder = os.path.join(project_dir, "src", "DataProcessors", processor, "Templates", template)
    if not os.path.isdir(folder):
        return {}
    return {name: os.path.getsize(os.path.join(folder, name)) for name in sorted(os.listdir(folder))
            if os.path.isfile(os.path.join(folder, name))}


def mdo_templates(project_dir, processor):
    """Макеты из .mdo обработки: имя → templateType (у табличного EDT тип не пишет)."""
    mdo_file = os.path.join(project_dir, "src", "DataProcessors", processor, processor + ".mdo")
    if not os.path.exists(mdo_file):
        return {}
    root = ET.parse(mdo_file).getroot()
    return {t.findtext("name"): t.findtext("templateType") or "SpreadsheetDocument" for t in root.findall("templates")}


def wait_for(predicate, seconds=30):
    """Перенос файлов рефакторингом EDT может закончиться позже ответа тула."""
    deadline = time.time() + seconds
    while True:
        if predicate():
            return True
        if time.time() >= deadline:
            return False
        time.sleep(1)


def check_templates(mcp, report, project, project_dir, suffix):
    """Файл содержимого макета по его типу (Gitea androman/codepilot1c#121).

    Eval scripts/run-template-artifact-eval.sh не достаёт место вызова в addMetadataChild: цикл по пакету
    children и текст ответа. А что EDT считает файл содержимым этого макета, доказывает рефакторинг:
    rename_metadata переносит признанный файл в папку нового имени и не трогает непризнанный. Замер
    17.09.2026 на сборке 1.3.9.20260916-2044: Template.bin нового макета переехал, Template.htmldoc EDT
    переписала из BM (CRLF), а Template.mxl у двоичного макета старой сборки остался в старой папке.
    Простые пробы не различают: диагностика пуста и у сломанного макета, delete_metadata не убирает файл
    ни у какого, edt_metadata_details содержимого не показывает.
    """
    processor = "ПроверкаМакетов" + suffix
    owner_fqn = f"DataProcessor.{processor}"
    print(f"\n== Макеты: {owner_fqn}")
    is_error, text = mutate(mcp, project, "create_metadata",
                            {"project": project, "kind": "DataProcessor", "name": processor, "synonym": "Проверка макетов"})
    if not report.check("подготовка: create_metadata DataProcessor", not is_error, text[:300]):
        return
    child = {"project": project, "parent_fqn": owner_fqn, "child_kind": "Template"}

    def artifact(name, file_name):
        return f"Template artifact: src/DataProcessors/{processor}/Templates/{name}/{file_name}"

    for name, template_type, file_name in (("Текст", "text", "Template.txt"),
                                           ("Двоичный", "binary", "Template.bin"),
                                           ("Страница", "html", "Template.htmldoc"),
                                           ("ТекстИменемEDT", "TextDocument", "Template.txt")):
        is_error, text = mutate(mcp, project, "add_metadata_child", dict(child, name=name, template_type=template_type))
        report.check(f"{template_type}: ответ называет {file_name}", not is_error and artifact(name, file_name) in text,
                     text[:300])
        files = template_files(project_dir, processor, name)
        empty = file_name != "Template.htmldoc"
        report.check(f"{template_type}: в папке макета ровно {file_name}" + (", пустой" if empty else ""),
                     list(files) == [file_name] and (not empty or files[file_name] == 0), str(files))
    page = os.path.join(project_dir, "src", "DataProcessors", processor, "Templates", "Страница", "Template.htmldoc")
    root = ET.parse(page).getroot() if os.path.exists(page) else None
    report.check("html: HtmlDocument без страниц в пространстве имён EDT",
                 root is not None and root.tag == "{http://g5.1c.ru/v8/dt/html-document}HtmlDocument" and len(root) == 0,
                 ET.tostring(root, encoding="unicode")[:200] if root is not None else "файла нет")

    is_error, text = mutate(mcp, project, "add_metadata_child", dict(child, name="Схема", template_type="dcs"))
    report.check("dcs: макет без файла и без предупреждения",
                 not is_error and "WARNING" not in text and not template_files(project_dir, processor, "Схема"), text[:300])
    # Табличная ветка оставлена как была (#122): файл либо подтверждён, либо ответ говорит, что нет.
    is_error, text = mutate(mcp, project, "add_metadata_child", dict(child, name="Таблица", template_type="spreadsheet"))
    report.check("spreadsheet: ответ не молчит о файле содержимого",
                 not is_error and (artifact("Таблица", "Template.mxl") in text
                                   or "WARNING: template content file not confirmed for Таблица (Template.mxl)" in text),
                 text[:300])

    batch = (("ПакетТекст", "text", "Template.txt"), ("ПакетДвоичный", "binary", "Template.bin"),
             ("ПакетСтраница", "html", "Template.htmldoc"))
    is_error, text = mutate(mcp, project, "add_metadata_child",
                            dict(child, properties={"children": [{"name": n, "template_type": t} for n, t, _ in batch]}))
    report.check("пакет children: ответ называет артефакты всех детей",
                 not is_error and all(artifact(n, f).split("Template artifact: ")[1] in text for n, _, f in batch), text[:400])
    for name, _, file_name in batch:
        files = template_files(project_dir, processor, name)
        report.check(f"пакет: {name} получил {file_name}", list(files) == [file_name], str(files))
    report.check("пакет: нет Templates/null",
                 not os.path.exists(os.path.join(project_dir, "src", "DataProcessors", processor, "Templates", "null")))

    for label, payload, marker in (
            ("активный документ", dict(child, name="Активный", template_type="active_document"), "Template.axdt"),
            ("неизвестный template_type", dict(child, name="Неизвестный", properties={"template_type": "presentation"}),
             "presentation"),
            ("внешняя компонента у ребёнка пакета",
             dict(child, properties={"children": [{"name": "ОтказТекст", "template_type": "text"},
                                                  {"name": "ОтказКомпонента", "template_type": "addin"}]}),
             "Template.addin")):
        is_error, text = mutate(mcp, project, "add_metadata_child", payload)
        report.check(f"{label}: отклонён уже на validate (INVALID_PROPERTY_VALUE)",
                     is_error and text.startswith("validate:") and "INVALID_PROPERTY_VALUE" in text and marker in text,
                     text[:300])
    created = mdo_templates(project_dir, processor)
    report.check(".mdo: отклонённые макеты не созданы",
                 not {"Активный", "Неизвестный", "ОтказТекст", "ОтказКомпонента"} & set(created), str(sorted(created)))
    report.check(".mdo: типы созданных макетов", created == {
        "Текст": "TextDocument", "Двоичный": "BinaryData", "Страница": "HTMLDocument", "ТекстИменемEDT": "TextDocument",
        "Схема": "DataCompositionSchema", "Таблица": "SpreadsheetDocument", "ПакетТекст": "TextDocument",
        "ПакетДвоичный": "BinaryData", "ПакетСтраница": "HTMLDocument"}, str(created))

    # Признаёт ли EDT файл содержимым: рефакторинг переносит признанный файл. Контроль — чужой файл в папке
    # макета СКД, у которого своего файла нет: он обязан остаться, иначе проба переносит что угодно.
    stray = f"{project}/src/DataProcessors/{processor}/Templates/Схема/Лишний.md"
    is_error, text = mcp.call("write_file", {"path": stray, "content": "не содержимое макета\n", "overwrite": True})
    if not report.check("контроль: в папку макета СКД записан чужой файл",
                        not is_error and "Лишний.md" in template_files(project_dir, processor, "Схема"), text[:300]):
        return
    for name, file_name in (("Схема", "Лишний.md"), ("Двоичный", "Template.bin"), ("Страница", "Template.htmldoc"),
                            ("ТекстИменемEDT", "Template.txt")):
        renamed = name + "Переименован"
        is_error, text = mutate(mcp, project, "rename_metadata",
                                {"project": project, "target_fqn": f"{owner_fqn}.Template.{name}", "new_name": renamed})
        if not report.check(f"rename {name} → {renamed}", not is_error, text[:300]):
            continue
        if name == "Схема":
            moved = wait_for(lambda: file_name in template_files(project_dir, processor, renamed), seconds=10)
            report.check("контроль: чужой файл рефакторинг не перенёс",
                         not moved and file_name in template_files(project_dir, processor, name),
                         f"{name}: {template_files(project_dir, processor, name)}, {renamed}: "
                         f"{template_files(project_dir, processor, renamed)}")
        else:
            moved = wait_for(lambda: file_name in template_files(project_dir, processor, renamed)
                             and file_name not in template_files(project_dir, processor, name))
            report.check(f"EDT признала {file_name} содержимым: рефакторинг перенёс его к {renamed}", moved,
                         f"{name}: {template_files(project_dir, processor, name)}, {renamed}: "
                         f"{template_files(project_dir, processor, renamed)}")


def main():
    parser = argparse.ArgumentParser(description=__doc__.splitlines()[0])
    parser.add_argument("--project", required=True)
    parser.add_argument("--project-dir", required=True)
    parser.add_argument("--import", dest="do_import", action="store_true")
    parser.add_argument("--xdto-check", default="")
    parser.add_argument("--skip-dcs", action="store_true")
    parser.add_argument("--skip-forms", action="store_true")
    parser.add_argument("--skip-policy", action="store_true")
    parser.add_argument("--skip-http", action="store_true")
    parser.add_argument("--skip-templates", action="store_true")
    parser.add_argument("--workspace-dir", default="",
                        help="каталог workspace EDT: режим хоста сверяется по настройкам codepilot")
    parser.add_argument("--reuse-suffix", default="",
                        help="суффикс объектов прошлого прогона: подготовка форм пропускается")
    args = parser.parse_args()

    mcp = Mcp(URL)
    try:
        mcp.open()
    except (urllib.error.URLError, OSError) as failure:
        print(f"ОКРУЖЕНИЕ: codepilot не отвечает на {URL}: {failure}")
        return 2
    if args.do_import:
        is_error, text = mcp.call("workspace_import_project", {"path": args.project_dir, "open": True, "refresh": True})
        print("import:", text[:200])
        if is_error:
            return 2
    suffix = args.reuse_suffix or datetime.datetime.now().strftime("%H%M%S")
    report = Report()
    if not args.skip_forms:
        check_forms(mcp, report, args.project, os.path.abspath(args.project_dir), suffix, bool(args.reuse_suffix))
    if not args.skip_dcs:
        check_dcs(mcp, report, args.project, os.path.abspath(args.project_dir), suffix, args.xdto_check)
    if not args.skip_policy:
        check_host_policy(mcp, report, args.project, os.path.abspath(args.project_dir), suffix, args.workspace_dir)
    if not args.skip_http:
        check_http(mcp, report, args.project, os.path.abspath(args.project_dir), suffix)
    if not args.skip_templates:
        check_templates(mcp, report, args.project, os.path.abspath(args.project_dir), suffix)
    print(f"\nИТОГ: зелёных {report.passed}, красных {report.failed}")
    return 1 if report.failed else 0


if __name__ == "__main__":
    raise SystemExit(main())
