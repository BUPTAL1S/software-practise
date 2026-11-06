package com.lab.sipim;
import com.lab.sipim.web.WorkbenchExportController;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;
class CsvExportTest {
    @Test void escapesSpreadsheetFormulasQuotesAndMultilineChinese() {
        assertEquals("\"'=SUM(A1:A2)\"",WorkbenchExportController.csvCell("=SUM(A1:A2)"));
        assertEquals("\"'  @unsafe\"",WorkbenchExportController.csvCell("  @unsafe"));
        assertEquals("\"中文,\"\"引号\"\"\n下一行\"",WorkbenchExportController.csvCell("中文,\"引号\"\n下一行"));
    }
}
