package client.command.commands.gm2;

import provider.Data;
import provider.DataProvider;
import provider.DataProviderFactory;
import provider.DataTool;
import provider.wz.WZFiles;

import java.util.ArrayList;
import java.util.List;

final class MapSearchHelper {
    private static final Data mapStringData;

    static {
        DataProvider dataProvider = DataProviderFactory.getDataProvider(WZFiles.STRING);
        mapStringData = dataProvider.getData("Map.img");
    }

    private MapSearchHelper() {
    }

    static List<MapMatch> findMaps(String search) {
        String normalizedSearch = search == null ? "" : search.toLowerCase();
        List<MapMatch> matches = new ArrayList<>();

        for (Data searchDataDir : mapStringData.getChildren()) {
            for (Data searchData : searchDataDir.getChildren()) {
                String mapName = DataTool.getString(searchData.getChildByPath("mapName"), "NO-NAME");
                String streetName = DataTool.getString(searchData.getChildByPath("streetName"), "NO-NAME");

                if (mapName.toLowerCase().contains(normalizedSearch)
                        || streetName.toLowerCase().contains(normalizedSearch)) {
                    matches.add(new MapMatch(Integer.parseInt(searchData.getName()), streetName, mapName));
                }
            }
        }

        return matches;
    }

    static MapMatch findFirstMap(String search) {
        List<MapMatch> matches = findMaps(search);
        return matches.isEmpty() ? null : matches.get(0);
    }

    record MapMatch(int mapId, String streetName, String mapName) {
    }
}
