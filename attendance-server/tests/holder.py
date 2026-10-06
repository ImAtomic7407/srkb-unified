"""Hold a file with the exact Windows share mode Excel uses (FILE_SHARE_READ).

Test scaffolding for excel_lock_guard. Excel COM cannot do file I/O from the
agent shell, so this stands in for the orphan EXCEL.EXE: same OS-level exclusive
handle, same PermissionError on the far end, and it can be killed the same way.
"""
import ctypes
import sys
import time
from ctypes import wintypes

GENERIC_READ = 0x80000000
GENERIC_WRITE = 0x40000000
OPEN_EXISTING = 3
# Excel shares the workbook for READING while it has it open -- that is why
# openpyxl's load_workbook() still succeeds against a locked master and only
# wb.save() raises. Denying reads too would be a stricter lock than the bug we
# are reproducing, and would make the test fail at load and never reach the save.
FILE_SHARE_READ = 0x00000001
FILE_ATTRIBUTE_NORMAL = 0x80
INVALID_HANDLE_VALUE = ctypes.c_void_p(-1).value

CreateFileW = ctypes.windll.kernel32.CreateFileW
CreateFileW.argtypes = [wintypes.LPCWSTR, wintypes.DWORD, wintypes.DWORD,
                        ctypes.c_void_p, wintypes.DWORD, wintypes.DWORD,
                        wintypes.HANDLE]
CreateFileW.restype = ctypes.c_void_p

path = sys.argv[1]
seconds = float(sys.argv[2]) if len(sys.argv) > 2 else 120.0

handle = CreateFileW(path, GENERIC_READ | GENERIC_WRITE, FILE_SHARE_READ, None,
                     OPEN_EXISTING, FILE_ATTRIBUTE_NORMAL, None)
if handle == INVALID_HANDLE_VALUE:
    print("HOLD_FAILED", ctypes.get_last_error(), flush=True)
    sys.exit(1)
print("HOLDING", flush=True)
time.sleep(seconds)
