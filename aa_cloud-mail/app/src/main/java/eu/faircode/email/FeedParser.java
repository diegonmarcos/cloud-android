package eu.faircode.email;

/*
    This file is part of FairEmail.

    FairEmail is free software: you can redistribute it and/or modify
    it under the terms of the GNU General Public License as published by
    the Free Software Foundation, either version 3 of the License, or
    (at your option) any later version.

    FairEmail is distributed in the hope that it will be useful,
    but WITHOUT ANY WARRANTY; without even the implied warranty of
    MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.  See the
    GNU General Public License for more details.

    You should have received a copy of the GNU General Public License
    along with FairEmail.  If not, see <http://www.gnu.org/licenses/>.

    Copyright 2018-2026 by Marcel Bokhorst (M66B)
*/

import android.text.TextUtils;
import android.util.Xml;

import org.xmlpull.v1.XmlPullParser;
import org.xmlpull.v1.XmlPullParserException;

import java.io.IOException;
import java.io.InputStream;
import java.text.ParseException;
import java.text.SimpleDateFormat;
import java.util.ArrayList;
import java.util.Date;
import java.util.List;
import java.util.Locale;

// comms: minimal RSS 2.0 + Atom feed parser, modeled on EmailProvider.parseProfiles()
public class FeedParser {
    // comms: a single parsed feed entry
    static class FeedItem {
        String guid;
        String title;
        String link;
        String html;
        long date;
    }

    // comms: a parsed feed — channel/feed-level title (used to auto-name the
    // folder when the user only pasted a URL) plus its items.
    static class Feed {
        String title;
        List<FeedItem> items = new ArrayList<>();
    }

    // RFC822 (RSS pubDate) and ISO8601 (Atom updated) date formats, tried in order
    private static final String[] DATE_FORMATS = new String[]{
            "EEE, dd MMM yyyy HH:mm:ss Z",
            "EEE, dd MMM yyyy HH:mm:ss zzz",
            "EEE, dd MMM yyyy HH:mm Z",
            "yyyy-MM-dd'T'HH:mm:ss.SSSXXX",
            "yyyy-MM-dd'T'HH:mm:ssXXX",
            "yyyy-MM-dd'T'HH:mm:ss'Z'",
            "yyyy-MM-dd'T'HH:mm:ss"
    };

    static Feed parse(InputStream is) throws XmlPullParserException, IOException {
        List<FeedItem> result = new ArrayList<>();
        String feedTitle = null;

        XmlPullParser xml = Xml.newPullParser();
        xml.setFeature(XmlPullParser.FEATURE_PROCESS_NAMESPACES, false);
        xml.setInput(is, null);

        FeedItem item = null;
        boolean atom = false; // <entry> vs RSS <item>
        String text = null;

        int eventType = xml.getEventType();
        while (eventType != XmlPullParser.END_DOCUMENT) {
            if (eventType == XmlPullParser.START_TAG) {
                String name = localName(xml.getName());
                if ("item".equalsIgnoreCase(name)) {
                    item = new FeedItem();
                    atom = false;
                } else if ("entry".equalsIgnoreCase(name)) {
                    item = new FeedItem();
                    atom = true;
                } else if (item != null && atom && "link".equalsIgnoreCase(name)) {
                    // Atom: <link href="..."/>, prefer rel="alternate" or no rel
                    String href = xml.getAttributeValue(null, "href");
                    String rel = xml.getAttributeValue(null, "rel");
                    if (href != null && (rel == null || "alternate".equalsIgnoreCase(rel)) &&
                            TextUtils.isEmpty(item.link))
                        item.link = href;
                }
                text = null;
            } else if (eventType == XmlPullParser.TEXT) {
                String chunk = xml.getText();
                if (chunk != null)
                    text = (text == null ? chunk : text + chunk);
            } else if (eventType == XmlPullParser.END_TAG) {
                String name = localName(xml.getName());
                if (item != null) {
                    if ("item".equalsIgnoreCase(name) || "entry".equalsIgnoreCase(name)) {
                        if (!TextUtils.isEmpty(item.title) || !TextUtils.isEmpty(item.link)) {
                            if (TextUtils.isEmpty(item.guid))
                                item.guid = (TextUtils.isEmpty(item.link) ? item.title : item.link);
                            if (item.date == 0)
                                item.date = new Date().getTime();
                            result.add(item);
                        }
                        item = null;
                    } else if ("title".equalsIgnoreCase(name)) {
                        if (text != null)
                            item.title = text.trim();
                    } else if ("link".equalsIgnoreCase(name)) {
                        // RSS: <link>text</link>
                        if (!atom && text != null && TextUtils.isEmpty(item.link))
                            item.link = text.trim();
                    } else if ("description".equalsIgnoreCase(name) ||
                            "summary".equalsIgnoreCase(name) ||
                            "content".equalsIgnoreCase(name) ||
                            "encoded".equalsIgnoreCase(name)) {
                        // RSS description / content:encoded, Atom summary / content
                        if (text != null && (TextUtils.isEmpty(item.html) || "content".equalsIgnoreCase(name) ||
                                "encoded".equalsIgnoreCase(name)))
                            item.html = text;
                    } else if ("guid".equalsIgnoreCase(name) || "id".equalsIgnoreCase(name)) {
                        if (text != null && TextUtils.isEmpty(item.guid))
                            item.guid = text.trim();
                    } else if ("pubDate".equalsIgnoreCase(name) ||
                            "updated".equalsIgnoreCase(name) ||
                            "published".equalsIgnoreCase(name) ||
                            "date".equalsIgnoreCase(name)) {
                        if (text != null)
                            item.date = parseDate(text.trim());
                    }
                } else if ("title".equalsIgnoreCase(name)) {
                    // comms: channel/feed-level <title> (outside any item/entry) —
                    // used to auto-name the folder when the user only pasted a URL.
                    if (feedTitle == null && !TextUtils.isEmpty(text))
                        feedTitle = text.trim();
                }
                text = null;
            }
            eventType = xml.next();
        }

        Feed feed = new Feed();
        feed.title = feedTitle;
        feed.items = result;
        return feed;
    }

    // Strip any namespace prefix (content:encoded -> encoded, dc:date -> date)
    private static String localName(String name) {
        if (name == null)
            return "";
        int colon = name.indexOf(':');
        return (colon < 0 ? name : name.substring(colon + 1));
    }

    private static long parseDate(String value) {
        if (TextUtils.isEmpty(value))
            return new Date().getTime();
        for (String format : DATE_FORMATS)
            try {
                SimpleDateFormat sdf = new SimpleDateFormat(format, Locale.US);
                Date date = sdf.parse(value);
                if (date != null)
                    return date.getTime();
            } catch (ParseException ignored) {
                // try next format
            }
        // fall back to now on parse failure
        return new Date().getTime();
    }
}
