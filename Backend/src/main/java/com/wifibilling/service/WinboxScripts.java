package com.wifibilling.service;

import com.wifibilling.model.WinboxAccess;
import static com.wifibilling.service.RouterScriptGenerator.q;

final class WinboxScripts {
    private WinboxScripts() {}

    static String installer(WinboxAccess a, String agent, String base) {
        String name = "ap-winbox-agent-" + a.getRouter().getId();
        String auth = "Authorization: Bearer " + agent;
        String state = "apWinboxRevision" + a.getRouter().getId();
        String body = ":global " + state + "; :do { :local response [/tool/fetch url=" + q(base + "/api/router/winbox/" + a.getRouter().getId() + "/config.rsc")
                + " http-header-field=" + q(auth) + " check-certificate=yes output=user as-value];"
                + " :local apply [:parse ($response->\"data\")]; $apply } on-error={ :set " + state + " \"\"; " + disable(a)
                + "; :log warning \"AirPairy WinBox agent unavailable; dedicated access disabled\" }";
        return "/system/scheduler/remove [find name=" + q(name) + "]\n"
                + "/system/script/remove [find name=" + q(name) + "]\n"
                + "/system/script/add name=" + q(name) + " policy=read,write,policy,password,sensitive,test source=" + q(body) + "\n"
                + "/system/scheduler/add name=" + q(name) + " interval=30s policy=read,write,policy,password,sensitive,test on-event=" + q("/system/script/run " + name) + "\n"
                + ":global " + state + "; :set " + state + " \"\"\n"
                + "/system/script/run " + q(name) + "\n";
    }

    static String configure(WinboxAccess a, boolean enabled, String password, String hub, String base, String agent) {
        String user = q(a.getUsername());
        String tag = q("ap-winbox:" + a.getRouter().getId());
        String auth = " http-header-field=" + q("Authorization: Bearer " + agent) + " check-certificate=yes http-method=post output=none";
        String ack = base + "/api/router/winbox/" + a.getRouter().getId() + "/ack?revision=" + a.getRevision();
        String changes = disable(a) + ";\n";
        if (enabled) {
            String wan = a.getRouter().getWanInterface();
            if (wan == null || !wan.matches("[A-Za-z0-9_.:-]{1,32}")) throw new IllegalStateException("Invalid enrolled WAN interface");
            String allowTag = q("ap-winbox-allow:" + a.getRouter().getId());
            String dropTag = q("ap-winbox-drop:" + a.getRouter().getId());
            String wanTag = q("ap-winbox-wan:" + a.getRouter().getId());
            String guardTag = q("ap-winbox-guard:" + a.getRouter().getId());
            String v6Tag = q("ap-winbox-ipv6:" + a.getRouter().getId());
            changes = ":foreach old in=[/user/find where comment~\"^ap-winbox:\"] do={ :if ([/user/get $old name] != " + user + ") do={ /user/set $old disabled=yes } }\n"
                    + ":if ([/ip/service/get [find name=winbox] port] != 8291) do={ :error \"WinBox must use port 8291\" }\n"
                    + ":if ([:len [/interface/find name=\"wg-billing\"]] = 0) do={ :error \"Billing tunnel unavailable\" }\n"
                    + ":if ([:len [/interface/list/find name=LAN]] = 0) do={ :error \"An existing LAN interface list is required\" }\n"
                    + ":if ([:len [/user/find name=" + user + "]] > 0) do={ :if ([/user/get [find name=" + user + "] comment] != " + tag + ") do={ :error \"Dedicated account name collision\" } }\n"
                    + ":if ([:len [/user/group/find name=" + user + "]] = 0) do={ /user/group/add name=" + user + " policy=local,telnet,ssh,ftp,reboot,read,write,policy,test,winbox,password,web,sniff,sensitive,api,romon,rest-api } else={ /user/group/set [find name=" + user + "] policy=local,telnet,ssh,ftp,reboot,read,write,policy,test,winbox,password,web,sniff,sensitive,api,romon,rest-api }\n"
                    + ":if ([:len [/user/find name=" + user + "]] = 0) do={ /user/add name=" + user + " group=" + user + " comment=" + tag + " disabled=yes }\n"
                    + "/user/set [find name=" + user + "] group=" + user + " address=" + q(hub + "/32") + " password=" + q(password) + " disabled=no\n"
                    + firstRule("/ip/firewall/filter", wanTag, "chain=input protocol=tcp dst-port=8291 in-interface=" + q(wan) + " action=drop")
                    + firstRule("/ip/firewall/filter", guardTag, "chain=input protocol=tcp dst-port=8291 in-interface=!wg-billing in-interface-list=!LAN action=drop")
                    + firstRule("/ip/firewall/filter", dropTag, "chain=input protocol=tcp dst-port=8291 in-interface=wg-billing action=drop")
                    + firstRule("/ip/firewall/filter", allowTag, "chain=input protocol=tcp dst-port=8291 in-interface=wg-billing src-address=" + q(hub + "/32") + " action=accept")
                    + firstRule("/ipv6/firewall/filter", v6Tag, "chain=input protocol=tcp dst-port=8291 in-interface-list=!LAN action=drop")
                    + ":local allowed [/ip/service/get [find name=winbox] address]; :local found false; :foreach prefix in=$allowed do={ :if ([:tostr $prefix] = " + q(hub + "/32") + ") do={ :set found true } }; :if ([:len $allowed] > 0 && !$found) do={ /ip/service/set [find name=winbox] address=($allowed," + q(hub + "/32") + ") }\n"
                    + "/ip/service/set [find name=winbox] disabled=no\n";
        }
        String marker = q(a.getPort() + ":" + a.getRevision());
        String state = "apWinboxRevision" + a.getRouter().getId();
        return ":global " + state + "; :do {\n:if ($" + state + " != " + marker + ") do={\n" + changes
                + ":set " + state + " " + marker + "\n}\n/tool/fetch url=" + q(ack + "&success=true") + auth
                + "\n} on-error={ :set " + state + " \"\"; " + disable(a) + "; /tool/fetch url=" + q(ack + "&success=false") + auth + "; :error \"WinBox configuration failed\" }\n";
    }

    private static String firstRule(String path, String tag, String options) {
        String rule = path + "/add " + options + " comment=" + tag;
        return ":if ([:len [" + path + "/find comment=" + tag + "]] = 0) do={ :if ([:len [" + path + "/find]] > 0) do={ "
                + rule + " place-before=0 } else={ " + rule + " } }\n";
    }

    private static String disable(WinboxAccess a) {
        return ":foreach u in=[/user/find where name=" + q(a.getUsername()) + " comment=" + q("ap-winbox:" + a.getRouter().getId())
                + "] do={ /user/set $u disabled=yes }";
    }
}
