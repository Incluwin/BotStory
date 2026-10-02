package server.knowledge.export;

import javax.xml.stream.XMLInputFactory;
import javax.xml.stream.XMLStreamConstants;
import javax.xml.stream.XMLStreamException;
import javax.xml.stream.XMLStreamReader;
import java.io.IOException;
import java.io.InputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

/**
 * Streaming reader for the WZ XML dump: builds a small tree of only the value nodes the exporter needs
 * (imgdir/int/short/long/string/float/double), skipping canvases, vectors and any root child the caller
 * does not keep. With a {@code stopAfter} set it stops reading once those root children have been read,
 * so a 40 KB equip file costs one {@code info} node, not its animation frames.
 */
final class WzXml {
    private static final XMLInputFactory FACTORY = XMLInputFactory.newFactory();
    private static final Set<String> VALUE_TAGS = Set.of("imgdir", "int", "short", "long", "string", "float", "double");

    static {
        FACTORY.setProperty(XMLInputFactory.SUPPORT_DTD, false);
        FACTORY.setProperty(XMLInputFactory.IS_COALESCING, false);
    }

    /** Which nodes to keep: {@code depth} 1 is a child of the root imgdir. */
    interface Keep {
        boolean keep(int depth, String name);
    }

    static final Keep ALL = (depth, name) -> true;

    static final class Node {
        final String name;
        final String value;
        final List<Node> children = new ArrayList<>(4);

        Node(String name, String value) {
            this.name = name;
            this.value = value;
        }

        Node child(String childName) {
            for (Node child : children) {
                if (child.name.equals(childName)) {
                    return child;
                }
            }
            return null;
        }

        Node at(String path) {
            Node node = this;
            for (String part : path.split("/")) {
                node = node.child(part);
                if (node == null) {
                    return null;
                }
            }
            return node;
        }

        int intAt(String path, int fallback) {
            Node node = at(path);
            return node == null ? fallback : parseInt(node.value, fallback);
        }

        String stringAt(String path) {
            Node node = at(path);
            return node == null ? null : node.value;
        }
    }

    private WzXml() {
    }

    static Node read(Path file, Keep keep, Set<String> stopAfter) throws IOException {
        try (InputStream in = Files.newInputStream(file)) {
            XMLStreamReader xml = FACTORY.createXMLStreamReader(in, "UTF-8");
            try {
                return read(xml, keep, stopAfter);
            } finally {
                xml.close();
            }
        } catch (XMLStreamException failure) {
            throw new IOException(file + ": " + failure.getMessage(), failure);
        }
    }

    private static Node read(XMLStreamReader xml, Keep keep, Set<String> stopAfter) throws XMLStreamException {
        Node root = null;
        List<Node> stack = new ArrayList<>();
        Set<String> pending = stopAfter == null ? null : new HashSet<>(stopAfter);
        int depth = -1;
        int skipDepth = -1;
        while (xml.hasNext()) {
            int event = xml.next();
            if (event == XMLStreamConstants.START_ELEMENT) {
                depth++;
                if (skipDepth >= 0) {
                    continue;
                }
                String tag = xml.getLocalName();
                String name = xml.getAttributeValue(null, "name");
                if (!VALUE_TAGS.contains(tag) || depth > 0 && !keep.keep(depth, name == null ? "" : name)) {
                    skipDepth = depth;
                    continue;
                }
                Node node = new Node(name == null ? "" : name, xml.getAttributeValue(null, "value"));
                if (root == null) {
                    root = node;
                } else {
                    stack.get(stack.size() - 1).children.add(node);
                }
                stack.add(node);
            } else if (event == XMLStreamConstants.END_ELEMENT) {
                if (skipDepth >= 0) {
                    if (depth == skipDepth) {
                        skipDepth = -1;
                    }
                    depth--;
                    continue;
                }
                Node closed = stack.remove(stack.size() - 1);
                if (depth == 1 && pending != null && pending.remove(closed.name) && pending.isEmpty()) {
                    return root;
                }
                depth--;
            }
        }
        return root;
    }

    static int parseInt(String value, int fallback) {
        if (value == null) {
            return fallback;
        }
        String text = value.trim();
        try {
            return Integer.parseInt(text);
        } catch (NumberFormatException notInt) {
            try {
                double number = Double.parseDouble(text.replace(',', '.'));
                return (int) Math.max(Integer.MIN_VALUE, Math.min(Integer.MAX_VALUE, number));
            } catch (NumberFormatException notNumber) {
                return fallback;
            }
        }
    }
}
