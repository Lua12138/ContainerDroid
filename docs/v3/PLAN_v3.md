# 背景
这是一个用于沙盒测试的安全应用，用于分析恶意软件。

# 目标

- 回顾自`5f097c84ede147483a4cb1919f4e9406b5b46ceb`这次git commit以来，至今所有文件，包含这一次本身的代码，要求补改变其原始含义的情况下，优化、重构其代码，要求其尽量可阅读，减少不必要的冗余，提升代码健壮性

# 环境与工具

可根据需要选择环境或工具

## Java 环境

根据不同使用场景，可以通过sdkman切换Java版本，例如编译时

- source "$HOME/.sdkman/bin/sdkman-init.sh"
- sdk use java 11.0.14.1-jbr
- ./gradlew assembleBlackBox32Debug

## JADX

功能：可以反编译apk/jar/dex文件

可执行文件位于`/home/fd/jadx/bin/jadx`，应使用无头模式，不要使用jadx-gui，具体的用法参考`https://github.com/skylot/jadx`

## IDA Pro

功能：可以反编译.so文件，查看反汇编和伪C代码

- 安装位置位于`/home/fd/ida-pro-9.1`，可以通过`idalib`来调用相关headless能力
- 应使用无头模式或自动模式，无交互模式，不应使用GUI
- 参考用法:
```python
#!/usr/bin/env python3
import argparse
import json
import os
import sys
from pathlib import Path

import idapro

# import idapro 之后，才能正常 import IDAPython 模块。
import ida_auto
import ida_entry
import ida_funcs
import ida_ida
import ida_nalt
import ida_name
import idaapi
import idautils
import idc


def hx(ea: int) -> str:
    return hex(int(ea))

def collect_metadata() -> dict:
    entry = None
    if ida_entry.get_entry_qty() > 0:
        ordinal = ida_entry.get_entry_ordinal(0)
        ea = ida_entry.get_entry(ordinal)
        if ea != idaapi.BADADDR:
            entry = hx(ea)

    return {
        "input_file": idaapi.get_input_file_path(),
        "root_filename": idaapi.get_root_filename(),
        "imagebase": hx(idaapi.get_imagebase()),
        "min_ea": hx(ida_ida.inf_get_min_ea()),
        "max_ea": hx(ida_ida.inf_get_max_ea()),
        "is_64bit": bool(ida_ida.inf_is_64bit()),
        "processor": ida_ida.inf_get_procname(),
        "file_size": int(ida_nalt.retrieve_input_file_size()),
        "md5": ida_nalt.retrieve_input_file_md5(),
        "sha256": ida_nalt.retrieve_input_file_sha256(),
        "entry_point": entry,
    }


def collect_functions(limit: int | None = None) -> list[dict]:
    out = []

    for ea in idautils.Functions():
        f = ida_funcs.get_func(ea)
        if not f:
            continue

        out.append({
            "ea": hx(ea),
            "name": ida_name.get_ea_name(ea),
            "start_ea": hx(f.start_ea),
            "end_ea": hx(f.end_ea),
            "size": int(f.end_ea - f.start_ea),
        })

        if limit is not None and len(out) >= limit:
            break

    return out


def collect_strings(limit: int | None = 2000) -> list[dict]:
    out = []

    for s in idautils.Strings():
        value = str(s)
        out.append({
            "ea": hx(s.ea),
            "length": int(s.length),
            "type": int(s.strtype),
            "value": value[:1000],
        })

        if limit is not None and len(out) >= limit:
            break

    return out


def analyze(input_file: str, output_file: str, save_idb: bool) -> None:
    input_file = str(Path(input_file).resolve())

    # 第二个参数 True 表示让 IDA 执行自动分析。
    # 官方示例也是 idapro.open_database(args.input_file, True)。:contentReference[oaicite:4]{index=4}
    rc = idapro.open_database(input_file, True)
    if rc != 0:
        raise RuntimeError(f"idapro.open_database() failed, rc={rc}, file={input_file}")

    try:
        ida_auto.auto_wait()

        result = {
            "ida_install_dir": idapro.get_ida_install_dir(),
            "ida_library_version": idapro.get_library_version(),
            "metadata": collect_metadata(),
            "functions": collect_functions(),
            "strings": collect_strings(),
        }

        with open(output_file, "w", encoding="utf-8") as fp:
            json.dump(result, fp, ensure_ascii=False, indent=2)

    finally:
        # save_idb=True 会保存 IDB/I64；False 则丢弃数据库修改。
        idapro.close_database(save_idb)


def main() -> int:
    parser = argparse.ArgumentParser()
    parser.add_argument("input", help="binary path")
    parser.add_argument("-o", "--output", default="ida_summary.json")
    parser.add_argument("--save-idb", action="store_true")
    args = parser.parse_args()

    analyze(args.input, args.output, args.save_idb)
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
```
  - 更多详细用法，需要联网搜索或参考文档`https://python.docs.hex-rays.com/`

# 验收标准

要求修改后的代码，仍然可以通过测试流程，标准不变

## 测试流程

你可以通过执行脚本`/home/fd/BlackBox/script/install-to-device.sh <pkg>`在真实设备上运行并测试，脚本结束后，对应的logcat日志可以在`/tmp/logcat.log`，对应的应用截图在`/tmp/screencap.png`

其中pkg参数可选:

- com.bestv.tv.video.iqy.tjdx
  - 这是目标恶意程序，目的是让这个程序可以在沙盒里运行
- com.example.tester
  - 这是一个样例程序，用于检测沙盒是否工作正常
  - 它应该会显示Apple.com的首页
  - 如果这个应用显示不正常或无法启动，代表沙盒应用本身已经修改出现问题，需要先修正沙盒自己的问题，再做反调试的工作
  - 当你认为此应用运行正常时，最后应当截图确认是否正常
  - 测试程序源代码位于`/home/fd/AndroidStudioProjects/Tester`
    - 需要做一些对比测试的时候，例如要对比正常系统与沙盒内获取系统属性，访问文件的结果等，你可以自己修改这个测试程序，然后分别安装到物理系统和沙盒环境进行对比，确认沙盒内的情况符合预期

注意：恶意程序在测试的真实环境也已经安装，你可以尝试直接在真实环境启动，对比logcat，查找差异点

你需要严格遵守项目的流程，确保本次修改不破坏原有功能，多联网搜搜，排查故障，完成后需要整体复盘，确认无误

需要严格确保上述两个pkg在沙盒内运行结果与沙盒外完全一致，同时还需要对比各个不同版本之间的功能一致性，尤其是启用了Proguard版本

## 失败条件

- 如果logcat中出现日志`BProcessManager: App Died: com.bestv.tv.video.iqy.tjdx`则代表一定失败，无需检查截图，可直接根据日志分析原因
- 如果日志里无显著错误，需要检查截图，要求**测试流程**里明确的两款应用在沙盒内与沙盒外的截图内容完全一致
