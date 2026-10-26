package com.example.claudemeals;

import jakarta.persistence.Column;
import jakarta.persistence.Embeddable;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;

/** One serving of a recipe ticks off {@code count} of a Daily Dozen item. */
@Embeddable
public class RecipeTick {

    @Enumerated(EnumType.STRING)
    @Column(name = "dozen_item")
    private DozenItem item;

    private int count;

    protected RecipeTick() {}

    public RecipeTick(DozenItem item, int count) {
        this.item = item;
        this.count = count;
    }

    public DozenItem getItem() { return item; }
    public void setItem(DozenItem item) { this.item = item; }
    public int getCount() { return count; }
    public void setCount(int count) { this.count = count; }
}
