package com.googlesource.gerrit.plugins.subreviewer;

import com.google.gerrit.extensions.events.UsageDataPublishedListener;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Usage data listener which logs data for test purposes.
 */
public class UsageDataListener implements UsageDataPublishedListener {
    private static final Logger log = LoggerFactory.getLogger(SubReviewerUtils.class);

    @Override
    public void onUsageDataPublished(Event event) {
        StringBuilder sb = new StringBuilder();

        sb.append("Usage Data:\n");

        MetaData m = event.getMetaData();
        sb.append("\nName: "); sb.append(m.getName());
        sb.append("\nDescription: "); sb.append(m.getDescription());
        sb.append("\nUnit name: "); sb.append(m.getUnitName());
        sb.append("\nUnit symbol: "); sb.append(m.getUnitSymbol());
        sb.append("\nTimestamp: "); sb.append(event.getInstant());
        sb.append("\n");

        for (Data d : event.getData()) {
            sb.append("\nProject name: "); sb.append(d.getProjectName());
            sb.append("\nValue: "); sb.append(d.getValue());
            sb.append("\n");
        }

        log.info(sb.toString());
    }
}
