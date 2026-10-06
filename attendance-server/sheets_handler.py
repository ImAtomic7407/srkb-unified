import gspread
from google.oauth2.service_account import Credentials
import os

# Configuration
CREDENTIALS_FILE = 'google_sheets_key.json'
SPREADSHEET_ID = os.environ.get('GOOGLE_SHEET_ID', '')  # id of your Google Sheet
ATTENDANCE_SHEET_HEADERS = [
    "Name",
    "Number",
    "Address",
    "Occupation",
    "Session",
    "Batch",
    "Rounds",
    "Remark",
    "Date",
]

def get_sheets_client():
    """Authenticates and returns the gspread client."""
    scopes = [
        "https://www.googleapis.com/auth/spreadsheets",
        "https://www.googleapis.com/auth/drive"
    ]
    
    if not os.path.exists(CREDENTIALS_FILE):
        raise FileNotFoundError(f"Credentials file '{CREDENTIALS_FILE}' not found in {os.getcwd()}")
        
    creds = Credentials.from_service_account_file(CREDENTIALS_FILE, scopes=scopes)
    client = gspread.authorize(creds)
    return client

def _resolve_spreadsheet_id(spreadsheet_id=None):
    return spreadsheet_id or SPREADSHEET_ID

def update_attendance_sheet(data, sheet_name="Sheet1", spreadsheet_id=None):
    """
    Updates the specified sheet with the provided data (list of dictionaries).
    """
    try:
        client = get_sheets_client()
        sh = client.open_by_key(_resolve_spreadsheet_id(spreadsheet_id))
        
        # Find or create the worksheet
        try:
            worksheet = sh.worksheet(sheet_name)
        except gspread.exceptions.WorksheetNotFound:
            worksheet = sh.add_worksheet(title=sheet_name, rows="1000", cols="20")
        
        # Clear existing data and update with new data
        worksheet.clear()
        
        if not data:
            return True
            
        # Prepare headers and rows
        headers = list(data[0].keys())
        rows = [list(item.values()) for item in data]
        
        worksheet.update([headers] + rows)
        return True
    except Exception as e:
        print(f"Error updating Google Sheet: {e}")
        return False

def append_row_to_google_sheet(data_row, sheet_name="MASTER_ALL", spreadsheet_id=None,
                               on_error=None):
    """
    Appends a single row to a Google sheet. Creates the sheet if it doesn't exist.
    data_row: dict with headers as keys

    Returns True only when the row actually reached the sheet, False otherwise.
    This function deliberately does NOT raise -- callers must check the return
    value. A caller that wraps this in try/except and reports success on "no
    exception" will report success for every failure, because there is never an
    exception to catch (this is exactly what happened on 2026-08-02: a DNS
    outage failed 16 of 23 appends while all 23 were logged as SUCCESS).

    on_error: optional callable taking one string. The reason is otherwise only
    print()ed to stdout, which lands in the pm2 log rather than the server log
    the operator actually reads.
    """
    try:
        client = get_sheets_client()
        sh = client.open_by_key(_resolve_spreadsheet_id(spreadsheet_id))
        
        # 1. Try to find or create worksheet
        try:
            worksheet = sh.worksheet(sheet_name)
        except gspread.exceptions.WorksheetNotFound:
            # Create new worksheet with headers if it doesn't exist
            worksheet = sh.add_worksheet(title=sheet_name, rows="100", cols="20")
            worksheet.append_row(ATTENDANCE_SHEET_HEADERS)

        # Keep the sheet header aligned with the attendance export schema.
        header_values = worksheet.row_values(1)
        if [str(v).strip().lower() for v in header_values[:len(ATTENDANCE_SHEET_HEADERS)]] != [h.lower() for h in ATTENDANCE_SHEET_HEADERS]:
            worksheet.update("A1", [ATTENDANCE_SHEET_HEADERS])
            
        # 2. Append data row (ensure values are in correct order matching headers)
        row_values = [
            str(data_row.get("name", "")),
            str(data_row.get("number", data_row.get("mobile", ""))),
            str(data_row.get("address", "")),
            str(data_row.get("occupation", "")),
            str(data_row.get("session", "")),
            str(data_row.get("batch", "")),
            str(data_row.get("rounds", "")),
            str(data_row.get("remark", "")),
            str(data_row.get("date", "")),
        ]
        worksheet.append_row(row_values)
        return True
    except Exception as e:
        msg = f"Google Sheets Append Error ({sheet_name}): {e}"
        print(msg)
        if on_error:
            try:
                on_error(msg)
            except Exception:
                pass  # a broken logger must never turn a sheet failure into a crash
        return False

def update_row_in_google_sheet(mobile, updated_data, sheet_name="MASTER_ALL", spreadsheet_id=None):
    """
    Finds a row by mobile number (Column B) and updates it.
    updated_data: dict with headers as keys (e.g. {'Name': 'New Name', 'Address': 'New Address'})
    """
    try:
        client = get_sheets_client()
        sh = client.open_by_key(_resolve_spreadsheet_id(spreadsheet_id))
        
        try:
            worksheet = sh.worksheet(sheet_name)
        except gspread.exceptions.WorksheetNotFound:
            return False
            
        # 1. Fetch all mobile numbers (Column B is index 2)
        mobiles = worksheet.col_values(2)
        
        # 2. Find row index (1-based for gspread)
        target_row_idx = -1
        mobile_str = str(mobile).strip()
        for i, val in enumerate(mobiles):
            if str(val).strip() == mobile_str:
                target_row_idx = i + 1
                break
                
        if target_row_idx == -1:
            print(f"Google Sheet update: Mobile {mobile} not found in {sheet_name}")
            return False
            
        # 3. Update columns. Map headers to column indices.
        # Header order: 1:Name, 2:Number, 3:Address, 4:Occupation, 5:Session, 6:Batch, 7:Rounds, 8:Remark, 9:Date
        # In the app, headers are sometimes "Number" and sometimes "Mobile".
        # We'll normalize to the standard sheet headers.
        header_map = {
            "name": 1, "Name": 1,
            "number": 2, "Number": 2, "mobile": 2, "Mobile": 2,
            "address": 3, "Address": 3,
            "occupation": 4, "Occupation": 4,
            "session": 5, "Session": 5,
            "batch": 6, "Batch": 6,
            "rounds": 7, "Rounds": 7,
            "remark": 8, "Remark": 8,
            "date": 9, "Date": 9,
            "cheating": 10, "Cheating": 10 # If present
        }
        
        # Build batch update list for efficiency
        updates = []
        for key, val in updated_data.items():
            if key in header_map:
                col_idx = header_map[key]
                updates.append({
                    'range': gspread.utils.rowcol_to_a1(target_row_idx, col_idx),
                    'values': [[str(val) if val is not None else ""]]
                })
        
        if updates:
            worksheet.batch_update(updates)
            return True
        return False
        
    except Exception as e:
        print(f"Google Sheets Update Error ({sheet_name}, {mobile}): {e}")
        return False

if __name__ == "__main__":
    # Test block
    print("Testing connection...")
    try:
        c = get_sheets_client()
        spreadsheet = c.open_by_key(SPREADSHEET_ID)
        print(f"Successfully connected to: {spreadsheet.title}")
    except Exception as e:
        print(f"Connection failed: {e}")
