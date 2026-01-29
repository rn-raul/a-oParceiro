package br.com.semalo.action;

import br.com.sankhya.extensions.actionbutton.AcaoRotinaJava;
import br.com.sankhya.extensions.actionbutton.ContextoAcao;
import br.com.sankhya.extensions.actionbutton.Registro;

import br.com.sankhya.jape.EntityFacade;
import br.com.sankhya.jape.core.JapeSession;
import br.com.sankhya.jape.core.JapeSession.SessionHandle;
import br.com.sankhya.jape.sql.NativeSql;
import br.com.sankhya.jape.dao.JdbcWrapper;
import br.com.sankhya.modelcore.util.EntityFacadeFactory;

import java.io.InputStream;
import java.net.HttpURLConnection;
import java.net.URL;
import java.nio.charset.StandardCharsets;
import java.sql.ResultSet;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

public class BuscarParceiro implements AcaoRotinaJava {

    private static final String BASE_URL = "https://receitaws.com.br/v1/cnpj/";

    // ✅ API CCC (IE)
    private static final String CCC_URL = "https://receitaws.com.br/v1/ccc/";
    private static final int CCC_DAYS = 0; 
    private static final String CCC_TOKEN = "2b1b7a78b86c6365f5a16c852f064fffb1861616844504e0fe3bb89b0db2da5e"; // Temporario
    @Override
    public void doAction(ContextoAcao ctx) throws Exception {

        Registro[] linhas = ctx.getLinhas();
        if (linhas == null || linhas.length == 0) {
            ctx.mostraErro("Selecione um parceiro para executar a ação.");
            return;
        }

        int preenchidos = 0;
        int ignorados = 0;
        StringBuilder nomesRetornados = new StringBuilder();

        // Sessão JAPE (necessária em muitos ambientes para usar JdbcWrapper/NativeSql)
        SessionHandle hnd = JapeSession.open();
        try {
            EntityFacade dwf = EntityFacadeFactory.getDWFFacade();
            JdbcWrapper jdbc = dwf.getJdbcWrapper();
            jdbc.openSession();

            try {
                for (Registro reg : linhas) {

                    String cgcCpf = onlyDigits(asString(reg.getCampo("CGC_CPF")));
                    if (cgcCpf == null || cgcCpf.length() != 14) {
                        ignorados++;
                        continue;
                    }

                    // =======================
                    // 1) ReceitaWS CNPJ
                    // =======================
                    String json = httpGet(BASE_URL + cgcCpf);

                    String status = jsonGet(json, "status");
                    if (status == null || !"OK".equalsIgnoreCase(status)) {
                        String msg = jsonGet(json, "message");
                        throw new RuntimeException("Receitaws retornou erro: " + (msg != null ? msg : json));
                    }

                    // Capturando dados da ReceitaWS
                    String razao = jsonGet(json, "nome");
                    String cep = onlyDigits(jsonGet(json, "cep"));
                    String numero = jsonGet(json, "numero");
                    String abertura = jsonGet(json, "abertura");
                    String email = jsonGet(json, "email");
                    String uf = jsonGet(json, "uf");

                    Integer codUf = buscarCodUf(jdbc, uf);
                    if (codUf != null) {
                        reg.setCampo("AD_TSIUFS", codUf);
                    }

                    // ✅ Preenche NA TELA (Registro) - FORÇADO
                    if (razao != null && !razao.trim().isEmpty()) reg.setCampo("NOMEPARC", razao);

                    if (cep != null && !cep.trim().isEmpty()) reg.setCampo("CEP", cep);
                    if (numero != null && !numero.trim().isEmpty()) reg.setCampo("NUMEND", numero);
                    if (abertura != null && !abertura.trim().isEmpty()) reg.setCampo("DTNASC", abertura);
                    if (email != null && !email.trim().isEmpty()) reg.setCampo("EMAIL", email);

                    // 🔥 Resolve CODCID/CODBAI/CODEND via TSICEP e preenche também
                    if (cep != null && !cep.trim().isEmpty()) {
                        CepVinculos vinc = buscarVinculosCep(jdbc, cep);
                        if (vinc != null) {
                            reg.setCampo("CODCID", vinc.codCid);
                            reg.setCampo("CODBAI", vinc.codBai);
                            reg.setCampo("CODEND", vinc.codEnd);
                        }
                    }

                    // =======================
                    // 2) ReceitaWS CCC (IE)
                    // =======================
                    // Ex: https://receitaws.com.br/v1/ccc/{cnpj}/days/{days}
                    String jsonCcc = httpGetBearer(
                            CCC_URL + cgcCpf + "/days/" + CCC_DAYS,
                            CCC_TOKEN
                    );

                    String statusCcc = jsonGet(jsonCcc, "status");
                    if (statusCcc != null && !"OK".equalsIgnoreCase(statusCcc)) {
                        String msg = jsonGet(jsonCcc, "message");
                        throw new RuntimeException("Receitaws CCC retornou erro: " + (msg != null ? msg : jsonCcc));
                    }

                    // IE vem dentro de registros[0].ie
                    String ie = extrairIeDoCcc(jsonCcc);

                    // ⚠️ Ajuste o nome do campo conforme seu dicionário TGFPAR:
                    // Alguns ambientes usam "IE", outros "IDENTINSCEST", etc.
                    if (ie != null && !ie.trim().isEmpty()) {
                        reg.setCampo("IDENTINSCESTAD", ie);
                    }

                    preenchidos++;

                    // Debug
                    String nomeDebug = (razao != null && !razao.trim().isEmpty()) ? razao : razao;
                    if (nomeDebug != null && !nomeDebug.trim().isEmpty()) {
                        if (nomesRetornados.length() > 0) nomesRetornados.append(", ");
                        nomesRetornados.append(nomeDebug).append(" (").append(cgcCpf).append(")");
                    }
                }
            } finally {
                jdbc.closeSession();
            }

        } finally {
            JapeSession.close(hnd);
        }

        ctx.setMensagemRetorno(
                "Parceiro cadastrado: " + nomesRetornados.toString() +
                " | Ignorados: " + ignorados
        );
    }

    // ===== TSICEP lookup (sem ns.close, compatível) =====
    private static CepVinculos buscarVinculosCep(JdbcWrapper jdbc, String cep) throws Exception {
        NativeSql ns = new NativeSql(jdbc);
        ResultSet rs = null;

        try {
            ns.appendSql("SELECT CODCID, CODBAI, CODEND FROM TSICEP WHERE CEP = :CEP");
            ns.setNamedParameter("CEP", cep);

            rs = ns.executeQuery();
            if (!rs.next()) return null;

            CepVinculos v = new CepVinculos();
            v.codCid = rs.getInt("CODCID");
            v.codBai = rs.getInt("CODBAI");
            v.codEnd = rs.getInt("CODEND");
            return v;

        } finally {
            if (rs != null) {
                try { rs.close(); } catch (Exception ignored) {}
            }
        }
    }

    private static class CepVinculos {
        int codCid;
        int codBai;
        int codEnd;
    }

    private static Integer buscarCodUf(JdbcWrapper jdbc, String uf) throws Exception {
        if (uf == null || uf.trim().isEmpty()) return null;

        NativeSql ns = new NativeSql(jdbc);
        ResultSet rs = null;

        try {
            ns.appendSql("SELECT CODUF FROM TSIUFS WHERE UF = :UF");
            ns.setNamedParameter("UF", uf.trim().toUpperCase());

            rs = ns.executeQuery();
            if (!rs.next()) return null;

            return rs.getInt("CODUF");

        } finally {
            if (rs != null) {
                try { rs.close(); } catch (Exception ignored) {}
            }
        }
    }

    // ===== HTTP / Helpers =====
    private static String httpGet(String urlStr) throws Exception {
        URL url = new URL(urlStr);
        HttpURLConnection conn = (HttpURLConnection) url.openConnection();
        conn.setRequestMethod("GET");
        conn.setRequestProperty("Accept", "application/json");
        conn.setRequestProperty("User-Agent", "SankhyaOM/ActionButton");
        conn.setConnectTimeout(15000);
        conn.setReadTimeout(15000);

        int code = conn.getResponseCode();
        InputStream is = (code >= 200 && code < 300) ? conn.getInputStream() : conn.getErrorStream();

        String body = readStream(is);

        if (code < 200 || code >= 300) {
            throw new RuntimeException("Erro HTTP " + code + " - " + body);
        }
        return body;
    }

    private static String httpGetBearer(String urlStr, String bearerToken) throws Exception {
        URL url = new URL(urlStr);
        HttpURLConnection conn = (HttpURLConnection) url.openConnection();
        conn.setRequestMethod("GET");
        conn.setRequestProperty("Accept", "application/json");
        conn.setRequestProperty("Authorization", "Bearer " + bearerToken);
        conn.setRequestProperty("User-Agent", "SankhyaOM/ActionButton");
        conn.setConnectTimeout(15000);
        conn.setReadTimeout(15000);

        int code = conn.getResponseCode();
        InputStream is = (code >= 200 && code < 300) ? conn.getInputStream() : conn.getErrorStream();

        String body = readStream(is);

        if (code < 200 || code >= 300) {
            throw new RuntimeException("Erro HTTP " + code + " - " + body);
        }
        return body;
    }

    private static String readStream(InputStream is) throws Exception {
        if (is == null) return "";
        byte[] buffer = new byte[4096];
        int bytesRead;
        StringBuilder sb = new StringBuilder();
        while ((bytesRead = is.read(buffer)) != -1) {
            sb.append(new String(buffer, 0, bytesRead, StandardCharsets.UTF_8));
        }
        return sb.toString();
    }

    private static String onlyDigits(String s) {
        if (s == null) return null;
        return s.replaceAll("\\D+", "");
    }

    private static String asString(Object o) {
        return (o == null) ? null : String.valueOf(o);
    }

    // Suporta: "chave":"valor" e "chave":null
    private static String jsonGet(String json, String key) {
        if (json == null) return null;

        Pattern p = Pattern.compile("\"" + Pattern.quote(key) + "\"\\s*:\\s*(\"(.*?)\"|null)",
                Pattern.CASE_INSENSITIVE | Pattern.DOTALL);
        Matcher m = p.matcher(json);
        if (!m.find()) return null;

        String val = m.group(2);
        if (val == null) return null;

        return val.replace("\\\"", "\"").trim();
    }

    // IE vem em registros[0].ie
    private static String extrairIeDoCcc(String json) {
        if (json == null) return null;

        Pattern p = Pattern.compile(
                "\"registros\"\\s*:\\s*\\[\\s*\\{[^}]*\"ie\"\\s*:\\s*\"(.*?)\"",
                Pattern.CASE_INSENSITIVE | Pattern.DOTALL
        );

        Matcher m = p.matcher(json);
        if (!m.find()) return null;

        String ie = m.group(1);
        return (ie == null) ? null : ie.trim();
    }
}
