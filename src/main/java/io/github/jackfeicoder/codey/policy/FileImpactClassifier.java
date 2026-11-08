package io.github.jackfeicoder.codey.policy;

import java.io.StringReader;
import java.util.Set;

/** 构建清单只在能够证明差异限于展示元数据时放宽；解析失败仍交用户确认。 */
public final class FileImpactClassifier {
    private FileImpactClassifier() { }
    public static boolean metadataOnly(String path, String before, String after) {
        try {
            if (path.endsWith("package.json")) return json(before).equals(json(after));
            if (path.endsWith("pom.xml")) return xml(before).isEqualNode(xml(after));
        } catch (Exception unknown) { /* 有执行语义或格式不明时保持敏感。 */ }
        return false;
    }
    private static com.fasterxml.jackson.databind.JsonNode json(String text) throws Exception {
        var mapper = com.fasterxml.jackson.databind.json.JsonMapper.builder()
                .enable(com.fasterxml.jackson.core.StreamReadFeature.STRICT_DUPLICATE_DETECTION)
                .enable(com.fasterxml.jackson.databind.DeserializationFeature.FAIL_ON_TRAILING_TOKENS).build();
        var value = mapper.readTree(text);
        if (!value.isObject()) throw new IllegalArgumentException();
        var copy = (com.fasterxml.jackson.databind.node.ObjectNode)value;
        copy.remove(Set.of("name", "description", "version", "license", "keywords")); return copy;
    }
    private static org.w3c.dom.Document xml(String text) throws Exception {
        var factory = javax.xml.parsers.DocumentBuilderFactory.newInstance();
        factory.setFeature("http://apache.org/xml/features/disallow-doctype-decl", true);
        factory.setFeature("http://xml.org/sax/features/external-general-entities", false);
        factory.setFeature("http://xml.org/sax/features/external-parameter-entities", false);
        factory.setAttribute(javax.xml.XMLConstants.ACCESS_EXTERNAL_DTD, "");
        factory.setAttribute(javax.xml.XMLConstants.ACCESS_EXTERNAL_SCHEMA, "");
        var builder = factory.newDocumentBuilder();
        builder.setErrorHandler(new org.xml.sax.helpers.DefaultHandler());
        var document = builder.parse(new org.xml.sax.InputSource(new StringReader(text)));
        var root = document.getDocumentElement();
        for (var node = root.getFirstChild(); node != null;) {
            var next = node.getNextSibling();
            if (node.getNodeType() == org.w3c.dom.Node.ELEMENT_NODE && Set.of("name", "description", "url").contains(node.getNodeName())) root.removeChild(node);
            node = next;
        }
        return document;
    }
}
