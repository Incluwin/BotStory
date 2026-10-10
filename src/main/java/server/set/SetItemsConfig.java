package server.set;

import java.util.ArrayList;
import java.util.List;

/**
 * Top-level wrapper that yamlbeans binds to. The YAML root is a map with a single
 * {@code sets:} key — this mirrors the existing {@code config.yaml} pattern.
 */
public class SetItemsConfig {
    public List<SetItemDef> sets = new ArrayList<>();
}
