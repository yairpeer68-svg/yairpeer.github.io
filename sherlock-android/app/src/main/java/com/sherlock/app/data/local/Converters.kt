package com.sherlock.app.data.local

import androidx.room.TypeConverter
import com.sherlock.app.data.model.SearchType
import com.sherlock.app.data.model.SiteCategory

class Converters {
    @TypeConverter
    fun fromSiteCategory(value: SiteCategory): String = value.name

    @TypeConverter
    fun toSiteCategory(value: String): SiteCategory = SiteCategory.valueOf(value)

    @TypeConverter
    fun fromSearchType(value: SearchType): String = value.name

    @TypeConverter
    fun toSearchType(value: String): SearchType = SearchType.valueOf(value)
}
