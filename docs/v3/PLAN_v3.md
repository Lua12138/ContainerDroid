这是一个用于沙盒测试的安全应用，用于分析恶意软件。现在需要新增一个功能，就是对于沙盒内应用的Binder调用监控，这个功能可以帮助研究员分析恶意软件的行为。

因此需要在Bcore项目内新建一个子项目black-binder来实现这个功能。项目结构与需要参考原本的组织方式

要求最终必须是可编译的

所有hook相关的内容，都应当**联网核对AOSP方法原型**

---

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

# 建议排查方向

- 通过使用seccomp或者inline hook拦截syscall/libc的打开文件方法，例如open方法，获取沙盒内容器具体访问了哪些文件
  - 逐一比对沙盒内外获取的文件内容，是否一致，是否存在明显特征
  - 对于沙盒内获取到内容不一致或者有明显特征的，应当通过IO重定向，避免沙盒内应用获取到特征信息
- 通过拦截Binder请求，判断沙盒内应用调用了哪些系统服务，做对应系统服务的代理，让沙盒应用无法感知环境不同
- 建议优先实现，dump dex，我作为人工可以协助你分析恶意程序的行为(将相关的dex保存在宿主files/<pkg>目录，pkg为沙盒应用的包名)
  - dump出来的文件名如果能获取到就以原始文件名，如果获取不到，就以文件内容的sha1值作为保存的文件，防止多次dump产生大量重复文件
  - 作为验证该功能是否正常，应当使用`jadx`工具反编译dump出来的恶意程序dex文件，其中应当包含com.bestv.tv相关的文件，否则就只是dump了加密壳的Stub
- 禁止尝试任何对于目标恶意程序的**硬编码拦截**，应该分析其判断出沙盒原因，而模拟/修改/代理一些底层或者系统方法，以实现一类共性问题的解决

# 测试反馈结果：

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

# 注意事项

对于你已经尝试过的方案，无效的，无法解决问题的，踩过的坑，这些失败的经验，必须要写入`/home/fd/BlackBox/experience_failure`目录下，对于相同方向的尝试，写入同一个文件，不同方向的尝试，写入不同文件。

每次尝试新方案前，都**必须读取**这个目录的所有文件，避免反复尝试相同的无效方案。但是要考虑多种原因组合的情况，如果曾经失败的原因与当前尝试的方案组合有，认为有可能构成恶意应用的检测点位，则应该组合多种方案共同尝试，如果仍然无效，则应记录相关失败方案。

要求在沙盒内运行的截图与物理设备一致，才算解决成功

对于环境模拟类的，经过测试，**如果无副作用，则无需回退，保留相应的修改，需要充分考虑到测试的恶意程序判断指标的多样性，单一特性修改可能难以避免其检测出沙盒**

# 一票否决

如果logcat中出现日志`BProcessManager: App Died: com.bestv.tv.video.iqy.tjdx`则代表一定失败，无需检查截图，可直接根据日志分析原因