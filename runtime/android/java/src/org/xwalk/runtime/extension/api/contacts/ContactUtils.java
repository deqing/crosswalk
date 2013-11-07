// Copyright (c) 2013 Intel Corporation. All rights reserved.
// Use of this source code is governed by a BSD-style license that can be
// found in the LICENSE file.

package org.xwalk.runtime.extension.api.contacts;

import org.xwalk.runtime.extension.api.contacts.ContactFinder.FindOption;

import android.content.ContentProviderOperation;
import android.content.ContentProviderResult;
import android.content.ContentResolver;
import android.content.ContentUris;
import android.content.OperationApplicationException;
import android.database.Cursor;
import android.net.Uri;
import android.os.RemoteException;
import android.provider.ContactsContract;
import android.provider.ContactsContract.CommonDataKinds.GroupMembership;
import android.provider.ContactsContract.Data;
import android.provider.ContactsContract.Groups;
import android.provider.ContactsContract.RawContacts;
import android.util.Log;

import java.text.ParseException;
import java.text.SimpleDateFormat;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.Map;
import java.util.Set;

public class ContactUtils {
    private final String mTag;
    public ContentResolver mResolver;
    public ContactUtils(ContentResolver resolver, String tag) {
        mResolver = resolver;
        mTag = tag;
    }

    public static <K, V> K getKeyFromValue(Map<K, V> map, V value) {
        K key = null;
        for (Map.Entry<K, V> entry : map.entrySet()) {
            if (value != null && value.equals(entry.getValue())) {
                key = entry.getKey();
                break;
            }
        }
        return key;
    }

    private enum ListOption { All, MimeType, Fields }
    public void listContactData() {
        listContactData(new FindOption(null, null, Data.CONTACT_ID), ListOption.Fields);
    }

    public void listContactData(FindOption findOption, ListOption option) {
        Cursor c = mResolver.query(RawContacts.CONTENT_URI, null,
                findOption.mWhere, findOption.mWhereArgs, findOption.mSortOrder);
        try {
            while (c.moveToNext()) {
                long id = c.getLong(c.getColumnIndex(RawContacts._ID));
                long contactId = c.getLong(c.getColumnIndex(RawContacts.CONTACT_ID));
                String accountName = c.getString(c.getColumnIndex(RawContacts.ACCOUNT_NAME));
                String accountType = c.getString(c.getColumnIndex(RawContacts.ACCOUNT_TYPE));
                if (contactId != 0) {
                    Log.i(mTag, id
                          + ", contactId: " + contactId
                          + ", accountName: " + accountName
                          + ", accountType: " + accountType
                          );
                }
            }

            c = mResolver.query(Data.CONTENT_URI, null,
                    findOption.mWhere, findOption.mWhereArgs, findOption.mSortOrder);
            while (c.moveToNext()) {
                long contactID = c.getLong(c.getColumnIndex(Data.CONTACT_ID));

                String output = "";
                String mime = c.getString(c.getColumnIndex(Data.MIMETYPE));
                mime = mime.substring(mime.indexOf('/')+1);
                if (option == ListOption.All) {
                    // This outputs all fields
                    output = String.valueOf(contactID);
                    for (String columnName : c.getColumnNames()) {
                        String value = c.getString(c.getColumnIndex(columnName));
                        output += ", " + columnName + ": " + value;
                    }
                    Log.i(mTag, output);
                } else if (option == ListOption.MimeType) {
                    // If we interested in some mimetype
                    if (mime.equals("phone_v2")) {
                        Log.i(mTag, output);
                    }
                } else if (option == ListOption.Fields) {
                    // If we interested in some specific fields
                    long rawId = c.getLong(c.getColumnIndex(Data.RAW_CONTACT_ID));
                    int prefer = c.getInt(c.getColumnIndex(Data.IS_SUPER_PRIMARY));
                    int dataVersion = c.getInt(c.getColumnIndex(Data.DATA_VERSION));
                    String data1 = c.getString(c.getColumnIndex(Data.DATA1));
                    String sync1 = c.getString(c.getColumnIndex(Data.SYNC1));
                    String status = c.getString(c.getColumnIndex(Data.STATUS));
                    int inVisibleGroup = c.getInt(c.getColumnIndex(Data.IN_VISIBLE_GROUP));
                    int inVisible = c.getInt(Data.INVISIBLE);
                    int available = c.getInt(Data.AVAILABLE);

                    String id = c.getString(c.getColumnIndex(Data._ID));
                    String contactPresence = c.getString(c.getColumnIndex(Data.CONTACT_PRESENCE));
                    String hasPhoneNumber = c.getString(c.getColumnIndex(Data.HAS_PHONE_NUMBER));
                    String starred = c.getString(c.getColumnIndex(Data.STARRED));

                    Log.i(mTag, contactID
                            + ", raw_id: " + rawId
                            + ", data1: " + data1
                            + ", mimetype: " + mime);
                }
            }
        } finally {
            c.close();
        }
    }

    /**
     * @param strings e.g. "apple", "orange", "banana"
     * @return A list of question marks to be used in SQL clause, e.g. "?,?,?"
     */
    public static String makeQuestionMarkList(Set<String> strings) {
        String ret = "";
        for (int i = 0; i < strings.size(); ++i) {
            ret += "?,";
        }
        return ret.substring(0, ret.length()-1);
    }

    public boolean hasID(String id) {
        if (id == null) {
            return false;
        }

        final Cursor c = mResolver.query(ContactsContract.Contacts.CONTENT_URI,
                null, ContactsContract.Contacts._ID + " = ?", new String[]{id}, null);
        return c.getCount() != 0;
    }

    public String getRawId(String id) {
        String rawContactId = null;
        Cursor c = mResolver.query(RawContacts.CONTENT_URI,
            new String[]{RawContacts._ID},
            RawContacts.CONTACT_ID + "=?",
            new String[]{id}, null);
        try {
            if (c.moveToFirst()) {
                // Actually it is possible that for one contact id there are multiple rawIds.
                rawContactId = c.getString(0);
            }
        } finally {
            c.close();
        }
        return rawContactId;
    }

    public String getId(String rawId) {
        String contactId = null;
        Cursor c = mResolver.query(RawContacts.CONTENT_URI,
            new String[]{RawContacts.CONTACT_ID},
            RawContacts._ID + "=?",
            new String[]{rawId}, null);
        try {
            if (c.moveToFirst()) {
                contactId = c.getString(0);
            }
        } finally {
            c.close();
        }
        return contactId;
    }

    public Set<String> getCurrentRawIds() {
        Set<String> rawIds = new HashSet<String>();
        Cursor c = mResolver.query(RawContacts.CONTENT_URI,
                new String[]{RawContacts._ID}, null, null, null);
        try {
            while (c.moveToNext()) {
                rawIds.add(c.getString(0));
            }
        } finally {
            c.close();
        }
        return rawIds;
    }

    public long getGroupId(long id) {
        Uri uri = Data.CONTENT_URI;
        String[] selectColumns = new String[] {GroupMembership.GROUP_ROW_ID};
        String where = String.format(
                "%s = ? AND %s = ?",
                Data.MIMETYPE,
                GroupMembership.CONTACT_ID);
        String[] whereParams = new String[] {GroupMembership.CONTENT_ITEM_TYPE, String.valueOf(id)};
        Cursor groupIdCursor = mResolver.query(
                uri,
                selectColumns,
                where,
                whereParams,
                null);
        try {
            if (groupIdCursor.moveToFirst()) {
                return groupIdCursor.getLong(0);
            }
            return -1; // No group found.
        } finally {
            groupIdCursor.close();
        }
    }

    public String[] getDefaultAccountNameAndType() {
        String accountType = "";
        String accountName = "";

        long rawContactId = 0;
        Uri rawContactUri = null;
        ContentProviderResult[] results = null;

        ArrayList<ContentProviderOperation> ops = new ArrayList<ContentProviderOperation>();

        ops.add(ContentProviderOperation.newInsert(RawContacts.CONTENT_URI)
                .withValue(RawContacts.ACCOUNT_NAME, null)
                .withValue(RawContacts.ACCOUNT_TYPE, null)
                .build());

        try {
            results = mResolver.applyBatch(ContactsContract.AUTHORITY, ops);
        } catch (RemoteException e) {
            e.printStackTrace();
        } catch (OperationApplicationException e) {
            e.printStackTrace();
        } finally {
            ops.clear();
        }

        for (ContentProviderResult result : results) {
            rawContactUri = result.uri;
            rawContactId = ContentUris.parseId(rawContactUri);
        }

        Cursor c = mResolver.query(
                RawContacts.CONTENT_URI
                , new String[] {RawContacts.ACCOUNT_TYPE, RawContacts.ACCOUNT_NAME}
                , RawContacts._ID+"=?"
                , new String[] {String.valueOf(rawContactId)}
                , null);

        if (c.moveToFirst()) {
            if (!c.isAfterLast()) {
                accountType = c.getString(c.getColumnIndex(RawContacts.ACCOUNT_TYPE));
                accountName = c.getString(c.getColumnIndex(RawContacts.ACCOUNT_NAME));
            }
        }

        mResolver.delete(rawContactUri, null, null);

        c.close();
        c = null;

        return new String[] { accountName, accountType };
    }

    public String getGroupId(String groupTitle) {
        final String selection = Groups.DELETED + "=? and " + Groups.GROUP_VISIBLE + "=?";
        Cursor cursor = mResolver.query(
                Groups.CONTENT_URI, null, selection, new String[]{"0", "1"}, null);
        cursor.moveToFirst();

        String groupId = null;
        for (int i = 0; i < cursor.getCount(); i++) {
            final String id = cursor.getString(cursor.getColumnIndex(Groups._ID));
            final String title = cursor.getString(cursor.getColumnIndex(Groups.TITLE));

            if (title.equals(groupTitle)) {
                groupId = id;
                break;
            }
            cursor.moveToNext();
        }
        cursor.close();

        return groupId;
    }

    public String getGroupTitle(String groupId) {
        final String selection = Groups.DELETED + "=? and " + Groups.GROUP_VISIBLE + "=?";
        Cursor cursor = mResolver.query(
                Groups.CONTENT_URI, null, selection, new String[]{"0", "1"}, null);
        cursor.moveToFirst();

        String groupTitle = null;
        for (int i = 0; i < cursor.getCount(); i++) {
            final String id = cursor.getString(cursor.getColumnIndex(Groups._ID));
            final String title = cursor.getString(cursor.getColumnIndex(Groups.TITLE));

            if (id.equals(groupId)) {
                groupTitle = title;
                break;
            }
            cursor.moveToNext();
        }
        cursor.close();

        return groupTitle;
    }

    public String getEnsuredGroupId(String groupTitle) {
        String groupId = getGroupId(groupTitle);
        if (groupId == null) {
            newGroup(groupTitle);
            groupId = getGroupId(groupTitle);
        }
        return groupId;
    }

    public void listGroups() {
        String selection = Groups.DELETED + "=? and " + Groups.GROUP_VISIBLE + "=?";
        Cursor cursor = mResolver.query(
                Groups.CONTENT_URI, null, selection, new String[]{"0", "1"}, null);
        cursor.moveToFirst();
        int len = cursor.getCount();
         for (int i = 0; i < len; i++) {
            String id = cursor.getString(cursor.getColumnIndex(Groups._ID));
            String title = cursor.getString(cursor.getColumnIndex(Groups.TITLE));
            String accountName = cursor.getString(cursor.getColumnIndex(Groups.ACCOUNT_NAME));
            String accountType = cursor.getString(cursor.getColumnIndex(Groups.ACCOUNT_TYPE));
            Log.i(mTag, "title: " + title
                    + " id: " + id
                    + " account: " + accountName
                    + " " + accountType);
            cursor.moveToNext();
        }
        cursor.close();
    }

    public void newGroup(String groupTitle) {
        final String accountNameType[] = getDefaultAccountNameAndType();
        ArrayList<ContentProviderOperation> o = new ArrayList<ContentProviderOperation>();
        o.add(ContentProviderOperation.newInsert(Groups.CONTENT_URI)
                .withValue(Groups.TITLE, groupTitle)
                .withValue(Groups.GROUP_VISIBLE, true)
                .withValue(Groups.ACCOUNT_NAME, accountNameType[0])
                .withValue(Groups.ACCOUNT_TYPE, accountNameType[1])
                .build());
        try {
            mResolver.applyBatch(ContactsContract.AUTHORITY, o);
        } catch (RemoteException e) {
            Log.e(mTag, "newGroup - Failed to create new contact group: " + e);
            return;
        } catch (OperationApplicationException e) {
            Log.e(mTag, "newGroup - Failed to create new contact group: " + e);
            return;
        }
    }

    public void cleanByMimeType(String id, String mimeType) {
        final String where = String.format(
                "%s = ? AND %s = ?",
                Data.CONTACT_ID, Data.MIMETYPE);
        final String[] whereParams = new String[] {id, mimeType};
        mResolver.delete(Data.CONTENT_URI, where, whereParams);
    }

    /**
     * Convert date from Android to JSON format.
     * @param cursor points to the record to be converted.
     * @param string indicates the column index.
     * @return A date string of JSON format
     */
    public String dateFormat(Cursor cursor, String string) {
        final SimpleDateFormat jsonDateFormat = new SimpleDateFormat(
                "yyyy-MM-dd'T'HH:mm:ss.SSS'Z'", java.util.Locale.getDefault());
        final SimpleDateFormat androidDateFormat = new SimpleDateFormat(
                "yyyy-MM-dd", java.util.Locale.getDefault());

        String date = null;
        try {
            date = jsonDateFormat.format(
                    androidDateFormat.parse(cursor.getString(cursor.getColumnIndex(string))));
        } catch (ParseException e) {
            Log.e(mTag, "dateFormat - parse failed: " + e);
        }
        return date;
    }

    /**
     * Convert date from JSON to Android format.
     * @param string the date to be converted.
     * @return A date string of Android format
     */
    public String dateFormat(String string) {
        final SimpleDateFormat jsonDateFormat = new SimpleDateFormat(
                "yyyy-MM-dd'T'HH:mm:ss.SSS'Z'", java.util.Locale.getDefault());
        final SimpleDateFormat androidDateFormat = new SimpleDateFormat(
                "yyyy-MM-dd", java.util.Locale.getDefault());

        String date = null;
        try {
            date = androidDateFormat.format(jsonDateFormat.parse(string));
        } catch (ParseException e) {
            Log.e(mTag, "dateFormat - parse failed: " + e);
        }
        return date;
    }
}
