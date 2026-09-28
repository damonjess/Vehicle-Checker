package com.example.vehiclechecker

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class MotApiClientTest {

    @Test
    fun testMapRecordToMotHistoryData() {
        val rawRecord = MotRecord(
            registration = "HV62JVK",
            make = "BMW",
            model = "3 SERIES",
            firstUsedDate = "2012-09-28 00:00:00",
            fuelType = "DIESEL",
            primaryColour = "BLACK",
            hasOutstandingRecall = "yes",
            motTests = listOf(
                MotTest(
                    completedDate = "2022-05-10 14:15:00", // Older test
                    testResult = "FAILED",
                    expiryDate = null,
                    odometerValue = "45000",
                    odometerUnit = "mi",
                    motTestNumber = "123456789011",
                    defects = listOf(
                        MotDefect(text = "Offside headlamp aim too low", type = "FAIL", dangerous = false)
                    )
                ),
                MotTest( // Newest test out of order
                    completedDate = "2023.05.15 10:30:00",
                    testResult = "PASSED",
                    expiryDate = "2024.05.14",
                    odometerValue = "83363", // Test conversion from km (83363 km ≈ 51800 mi)
                    odometerUnit = "km",
                    motTestNumber = "123456789012",
                    defects = listOf(
                        MotDefect(text = "Nearside front tyre worn close to legal limit", type = "MINOR"),
                        MotDefect(text = "Brake disc worn, pitted or weakened", type = "ADVISORY")
                    )
                ),
                MotTest(
                    completedDate = "2020.01.01",
                    testResult = "PASSED",
                    odometerResultType = "UNREADABLE",
                    odometerValue = "99999",
                    odometerUnit = "mi"
                )
            )
        )

        val history = MotApiClient.mapRecordToMotHistoryData(rawRecord, "HV62JVK")

        assertEquals("HV62JVK", history.registration)
        assertEquals("BMW", history.make)
        assertEquals("3 SERIES", history.model)
        assertEquals("BLACK", history.colour)
        assertEquals("DIESEL", history.fuelType)
        assertEquals("28 September 2012", history.dateRegistered)
        assertEquals("14 May 2024", history.motValidUntil)
        assertEquals(RecallStatus.OUTSTANDING, history.recallStatus)
        assertEquals(3, history.tests.size) // Sorted!

        // Verify test 1 (newest test, sorted first)
        val test1 = history.tests[0]
        assertEquals("15 May 2023", test1.dateTested)
        assertEquals("PASS", test1.result)
        assertTrue(test1.isPass)
        assertEquals(2023, test1.yearTested) // Should be four digit year
        assertEquals("51,799 miles", test1.mileage) // 83363 * 0.621371 = 51799.35
        assertEquals(51799, test1.mileageMiles)
        assertEquals(6799, test1.mileageDifference)
        assertEquals(2, test1.advisories.size)
        assertEquals("Nearside front tyre worn close to legal limit", test1.advisories[0])
        assertEquals("Brake disc worn, pitted or weakened", test1.advisories[1])

        // Verify test 2 (middle test)
        val test2 = history.tests[1]
        assertEquals("10 May 2022", test2.dateTested)
        assertEquals("FAIL", test2.result)
        assertFalse(test2.isPass)
        assertEquals(2022, test2.yearTested)
        assertEquals("45,000 miles", test2.mileage)
        assertEquals(45000, test2.mileageMiles)
        assertNull(test2.mileageDifference)
        assertTrue(test2.advisories.isEmpty())
        
        // Verify test 3 (oldest test, unreadable odometer)
        val test3 = history.tests[2]
        assertEquals("1 January 2020", test3.dateTested)
        assertEquals("", test3.mileage)
        assertNull(test3.mileageMiles)
    }
    
    @Test
    fun testEmptyTests() {
        val record = MotRecord(registration = "AB12CDE", motTests = emptyList())
        val history = MotApiClient.mapRecordToMotHistoryData(record, "AB12CDE")
        assertEquals("No MOT tests on record yet for AB12CDE", history.errorMessage)
    }

    @Test
    fun testParseJsonSingleObject() {
        val json = """{
            "registration": "AB12CDE",
            "make": "FORD",
            "motTests": [{"completedDate": "2023.01.01", "testResult": "PASSED"}]
        }"""
        val record = MotApiClient.parseMotRecordResponse(json)
        assertEquals("AB12CDE", record?.registration)
        assertEquals("FORD", record?.make)
        assertEquals(1, record?.motTests?.size)
    }

    @Test
    fun testParseJsonArray() {
        val json = """[{
            "registration": "AB12CDE",
            "make": "FORD",
            "motTests": [{"completedDate": "2023.01.01", "testResult": "PASSED"}]
        }]"""
        val record = MotApiClient.parseMotRecordResponse(json)
        assertEquals("AB12CDE", record?.registration)
        assertEquals("FORD", record?.make)
        assertEquals(1, record?.motTests?.size)
    }
}
