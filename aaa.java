package br.com.semalo.action;

import br.com.sankhya.extensions.actionbutton.AcaoRotinaJava;
import br.com.sankhya.extensions.actionbutton.ContextoAcao;
import br.com.sankhya.extensions.actionbutton.Registro;

import br.com.sankhya.jape.EntityFacade;
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
    //private static final String CCC_URL = "https://receitaws.com.br/v1/ccc/";
    //private static final int CCC_DAYS = 0; 
    //private static final String CCC_TOKEN = "2b1b7a78b86c6365f5a16c852f064fffb1861616844504e0fe3bb89b0db2da5e"; // Temporario
    @Override
    public void doAction(ContextoAcao ctx) throws Exception {
       // StringBuilder nomesRetornados = new StringBuilder();

        // Sessão JAPE (necessária em muitos ambientes para usar JdbcWrapper/NativeSql)
        	EntityFacade dwf = EntityFacadeFactory.getDWFFacade();
        	JdbcWrapper jdbc = dwf.getJdbcWrapper();
        	jdbc.openSession();

            try {
            	// =======================
            	// 0) CNPJ vindo do parâmetro
            	// =======================
            	String cgcCpf = onlyDigits((String) ctx.getParam("CNPJ"));
            	if (cgcCpf == null || cgcCpf.length() != 14) {
            	    ctx.mostraErro("Informe um CNPJ válido.");
            	    return;
            	}

            	// =======================
            	// 1) Cria NOVO parceiro
            	// =======================
            	Registro reg = ctx.novaLinha(); // TGFPAR

            	// Campo obrigatório
            	reg.setCampo("CGC_CPF", cgcCpf);
            	reg.setCampo("TIPPESSOA", "J"); // Jurídico

            	// =======================
            	// 2) ReceitaWS CNPJ
            	// =======================
            	String json = httpGet(BASE_URL + cgcCpf);

            	String status = jsonGet(json, "status");
            	if (status == null || !"OK".equalsIgnoreCase(status)) {
            	    String msg = jsonGet(json, "message");
            	    throw new RuntimeException("Receitaws retornou erro: " + (msg != null ? msg : json));
            	}

            	// =======================
            	// 3) Captura dados
            	// =======================
            	String razao    = jsonGet(json, "nome");
            	String cep      = onlyDigits(jsonGet(json, "cep"));
            	String numero   = jsonGet(json, "numero");
            	String abertura = jsonGet(json, "abertura");
            	String email    = jsonGet(json, "email");
            	String uf       = jsonGet(json, "uf");

            	// =======================
            	// 4) UF
            	// =======================
            	Integer codUf = buscarCodUf(jdbc, uf);
            	if (codUf != null) {
            	    reg.setCampo("AD_TSIUFS", codUf);
            	}

            	// =======================
            	// 5) Preenche parceiro
            	if (razao != null && !razao.trim().isEmpty())
            	    reg.setCampo("NOMEPARC", razao);

            	if (cep != null && !cep.trim().isEmpty())
            	    reg.setCampo("CEP", cep);

            	if (numero != null && !numero.trim().isEmpty())
            	    reg.setCampo("NUMEND", numero);

            	if (abertura != null && !abertura.trim().isEmpty())
            	    reg.setCampo("DTNASC", abertura);

            	if (email != null && !email.trim().isEmpty())
            	    reg.setCampo("EMAIL", email);


            	// =======================
            	// 6) CEP → Cidade/Bairro/Endereço
            	// =======================
            	if (cep != null && !cep.trim().isEmpty()) {
            	    CepVinculos vinc = buscarVinculosCep(jdbc, cep);
            	    if (vinc != null) {
            	        reg.setCampo("CODCID", vinc.codCid);
            	        reg.setCampo("CODBAI", vinc.codBai);
            	        reg.setCampo("CODEND", vinc.codEnd);
            	    }
            	}
            	ctx.setMensagemRetorno("Parceiro " + razao + " criado com sucesso!");
            	
            } finally {
                jdbc.closeSession();
            }
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
    /*
    private static String asString(Object o) {
        return (o == null) ? null : String.valueOf(o);
    }*/

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
}
