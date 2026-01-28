package br.com.semalo.action;

import br.com.sankhya.extensions.actionbutton.AcaoRotinaJava;
import br.com.sankhya.extensions.actionbutton.ContextoAcao;
import br.com.sankhya.extensions.actionbutton.Registro;

import java.io.InputStream;
import java.net.HttpURLConnection;
import java.net.URL;
import java.nio.charset.StandardCharsets;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

public class BotaoBuscarParceiro implements AcaoRotinaJava {

    private static final String BASE_URL = "https://receitaws.com.br/v1/cnpj/";

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

        for (Registro reg : linhas) {

            String cgcCpf = onlyDigits(asString(reg.getCampo("CGC_CPF")));
            if (cgcCpf == null || cgcCpf.length() != 14) {
                ignorados++;
                continue;
            }

            String json = httpGet(BASE_URL + cgcCpf);

            String status = jsonGet(json, "status");
            if (status == null || !"OK".equalsIgnoreCase(status)) {
                String msg = jsonGet(json, "message");
                throw new RuntimeException("Receitaws retornou erro: " + (msg != null ? msg : json));
            }

            // Receitaws
            String razao = jsonGet(json, "nome");
            String fantasia = jsonGet(json, "nome");
            String cep = onlyDigits(jsonGet(json, "cep"));
            //String logradouro = jsonGet(json, "logradouro");
            String numero = jsonGet(json, "numero");
            //String bairro = jsonGet(json, "bairro");
            //String uf = jsonGet(json, "uf");

            // ✅ Preenche NA TELA (Registro)
            // Nome/Fantasia: força preencher (pra você validar)
            if (razao != null && !razao.trim().isEmpty()) {
                reg.setCampo("NOMEPARC", razao);
            }
            if (fantasia != null && !fantasia.trim().isEmpty()) {
                reg.setCampo("RAZAOSOCIAL", fantasia);
            }

            // Endereço: você pode forçar também, ou só preencher se estiver vazio.
            // Aqui vou preencher só se estiver vazio (menos agressivo):
            setIfEmpty(reg, "CEP", cep);
            //setIfEmpty(reg, "ENDERECO", logradouro);
            setIfEmpty(reg, "NUMEND", numero);
            //setIfEmpty(reg, "BAIRRO", bairro);
            //setIfEmpty(reg, "UF", uf);

            preenchidos++;

            // Debug
            String nomeDebug = (fantasia != null && !fantasia.trim().isEmpty()) ? fantasia : razao;
            if (nomeDebug != null && !nomeDebug.trim().isEmpty()) {
                if (nomesRetornados.length() > 0) nomesRetornados.append(", ");
                nomesRetornados.append(nomeDebug).append(" (").append(cgcCpf).append(")");
            }
        }

        ctx.setMensagemRetorno(
            "Preenchido(s) na tela: " + preenchidos +
            " | Ignorados (sem CNPJ válido): " + ignorados +
            " | Nome(s) retornado(s): " + nomesRetornados.toString() +
            " | Agora clique em SALVAR no parceiro."
        );
    }

    private static void setIfEmpty(Registro reg, String campo, String valor) throws Exception {
        if (valor == null || valor.trim().isEmpty()) return;
        Object atualObj = reg.getCampo(campo);
        String atual = asString(atualObj);
        if (atual == null || atual.trim().isEmpty()) {
            reg.setCampo(campo, valor);
        }
    }

    private static String httpGet(String urlStr) throws Exception {
        URL url = new URL(urlStr);
        HttpURLConnection conn = (HttpURLConnection) url.openConnection();
        conn.setRequestMethod("GET");
        conn.setRequestProperty("Accept", "application/json");
        conn.setRequestProperty("User-Agent", "SankhyaOM/ActionButton");
        conn.setConnectTimeout(15000);
        conn.setReadTimeout(15000);

        int code = conn.getResponseCode();
        InputStream is = (code >= 200 && code < 300)
                ? conn.getInputStream()
                : conn.getErrorStream();

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
}
