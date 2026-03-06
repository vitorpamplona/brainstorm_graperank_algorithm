package org.example.grape;

import java.util.List;

public class GrapeRankInputs {
    private List<GrapeRankInput> items;

    public GrapeRankInputs(List<GrapeRankInput> items) {
        this.items = items;
    }

    public List<GrapeRankInput> getItems() {
        return items;
    }

    public void setItems(List<GrapeRankInput> items) {
        this.items = items;
    }
}