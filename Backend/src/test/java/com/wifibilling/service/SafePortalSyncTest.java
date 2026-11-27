package com.wifibilling.service;

import com.wifibilling.config.PlatformProperties;
import com.wifibilling.model.Router;
import com.wifibilling.repository.IpBindingRepository;
import com.wifibilling.repository.RouterRepository;
import com.wifibilling.repository.RouterTaskRepository;
import com.wifibilling.repository.WinboxAccessRepository;
import org.junit.jupiter.api.Test;
import org.springframework.test.util.ReflectionTestUtils;

import java.util.List;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

/**
 * The login-page sync that keeps the working page until a new download completes
 * (CALTON, 27 Nov 2026: weak uplink, fetch-over-the-file left no login page twice).
 */
class SafePortalSyncTest {

    private static final String BASE = "https://app.example.com";
    private static final String TOKEN = "tok_A1234567";
    private static final List<String> TEMPLATES = List.of("login.html", "feedback.html", "status.html", "logout.html",
            "alogin.html", "redirect.html", "rlogin.html", "error.html", "packages.json");

    private final String safe = RouterScriptGenerator.portalSyncScript(BASE, TOKEN, true);

    @Test
    void theDirectSyncIsUnchanged() {
        assertEquals(RouterScriptGenerator.portalSyncScript(BASE, TOKEN),
                RouterScriptGenerator.portalSyncScript(BASE, TOKEN, false));
        assertFalse(RouterScriptGenerator.portalSyncScript(BASE, TOKEN).contains("wbnew-"));
    }

    @Test
    void isOneBlockWithBalancedBracesSoLocalVariablesSurviveImport() {
        assertTrue(safe.startsWith(":do {\n"), safe);
        assertTrue(safe.endsWith("} on-error={ :log warning \"wifi-billing: portal sync failed\" }\n"), safe);
        int depth = 0;
        for (int i = 0; i < safe.length(); i++) {
            char c = safe.charAt(i);
            if (c == '"') { // skip quoted strings (q() escapes inner quotes)
                for (i++; i < safe.length() && safe.charAt(i) != '"'; i++) if (safe.charAt(i) == '\\') i++;
                continue;
            }
            if (c == '{') depth++;
            if (c == '}') depth--;
            assertTrue(depth >= 0, "unbalanced at " + i);
            if (c == '}' && depth == 0 && !safe.substring(i).trim().equals("}")) {
                // Only the final on-error may close the outer block.
                assertTrue(safe.substring(i).startsWith("} on-error={ :log warning \"wifi-billing: portal sync failed\""),
                        "outer block closes early at " + i);
            }
        }
        assertEquals(0, depth);
    }

    @Test
    void everyFileDownloadsToATempNameAndIsOnlyReplacedAfterACompletedDownload() {
        for (String t : TEMPLATES) {
            String url = BASE + "/api/portal/templates/" + t + "?token=" + TOKEN;
            String served = "dst-path=\"flash/hotspot/" + t + "\"";
            String temp = "dst-path=\"flash/hotspot/wbnew-" + t + "\"";
            int section = safe.indexOf(":local got false\n:do { /file remove [find name=\"flash/hotspot/wbnew-" + t + "\"]");
            assertTrue(section > 0, t + " has no section");
            int next = safe.indexOf(":local got false", section + 1);
            String body = safe.substring(section, next < 0 ? safe.length() : next);

            assertTrue(body.contains("url=\"" + url + "\" " + temp), t + " is not fetched to its temp name");
            // The served file is written only inside "download completed" and "size is plausible".
            int completed = body.indexOf(":if ($complete) do={");
            assertTrue(completed > 0, t);
            assertTrue(body.indexOf(":if ($got and ") < completed, t);
            int firstServedWrite = body.indexOf(served);
            int firstServedRemove = body.indexOf("/file remove [find name=\"flash/hotspot/" + t + "\"]");
            assertTrue(firstServedWrite > completed, t + " is written before the download is known complete");
            assertTrue(firstServedRemove > completed, t + " is removed before the download is known complete");
            assertTrue(body.contains("/file set [find name=\"flash/hotspot/wbnew-" + t + "\"] name=\"flash/hotspot/" + t + "\""), t);
        }
        assertTrue(safe.contains("size] < " + RouterScriptGenerator.MIN_LOGIN_PAGE_BYTES), "login page size check");
    }

    @Test
    void probesRenameSupportAndCleansUpItsProbeAndTempFiles() {
        assertTrue(safe.contains(":local wbRename false"));
        assertTrue(safe.contains("/file set [find name=\"" + RouterScriptGenerator.RENAME_PROBE_A + "\"] name=\""
                + RouterScriptGenerator.RENAME_PROBE_B + "\""));
        assertTrue(count(safe, "/file remove [find name=\"" + RouterScriptGenerator.RENAME_PROBE_B + "\"]") >= 2);
        // Reports to the server log that the whole script ran, and whether rename worked.
        assertTrue(safe.contains("/tool fetch url=(\"" + BASE + "/z/wb-portal-sync-done-rename-\" . $wbRename) output=none"), safe);
        for (String t : TEMPLATES) {
            // Removed before the download and again at the end of its section.
            assertEquals(2, count(safe, "/file remove [find name=\"flash/hotspot/wbnew-" + t + "\"]"), t);
        }
    }

    @Test
    void routerSelection() {
        PlatformProperties p = new PlatformProperties();
        ReflectionTestUtils.setField(p, "safePortalSyncRouterIds", "27");
        assertTrue(p.safePortalSync(27L));
        assertFalse(p.safePortalSync(45L));
        assertFalse(p.safePortalSync(null));
        ReflectionTestUtils.setField(p, "safePortalSyncRouterIds", " 27, 45 ");
        assertTrue(p.safePortalSync(45L));
        assertFalse(p.safePortalSync(2L));
        ReflectionTestUtils.setField(p, "safePortalSyncRouterIds", "*");
        assertTrue(p.safePortalSync(2L));
        ReflectionTestUtils.setField(p, "safePortalSyncRouterIds", "");
        assertFalse(p.safePortalSync(27L));
    }

    @Test
    void aSyncTaskForASafeRouterImportsTheScriptInsteadOfInliningIt() {
        PlatformProperties props = mock(PlatformProperties.class);
        when(props.getPublicBaseUrl()).thenReturn(BASE);
        when(props.safePortalSync(27L)).thenReturn(true);
        when(props.safePortalSync(45L)).thenReturn(false);
        RouterManagementService service = new RouterManagementService(mock(RouterRepository.class),
                mock(IpBindingRepository.class), mock(RouterTaskRepository.class), props,
                mock(RouterSetupService.class), mock(WinboxAccessRepository.class), mock(EncryptionService.class));

        String safeTask = service.scriptFor(Router.builder().id(27L).token(TOKEN).build(), "SYNC_PORTAL");
        assertTrue(safeTask.startsWith(RouterScriptGenerator.portalSyncBootstrap(BASE, TOKEN)), safeTask);
        assertFalse(safeTask.contains("wbnew-"), "the safe script must run from its own /import");

        String directTask = service.scriptFor(Router.builder().id(45L).token(TOKEN).build(), "SYNC_PORTAL");
        assertTrue(directTask.startsWith(RouterScriptGenerator.portalSyncScript(BASE, TOKEN)), "other routers unchanged");
    }

    private static int count(String haystack, String needle) {
        Matcher m = Pattern.compile(Pattern.quote(needle)).matcher(haystack);
        int n = 0;
        while (m.find()) n++;
        return n;
    }
}
