// Ported from the Dart SDK's sdk/lib/internal/sort.dart (Copyright (c) 2011, the Dart project authors, BSD-3-Clause). See NOTICE.md.
package com.haziaferi.scanknifeplus.scanner

/**
 * Dart's `List.sort`: insertion sort for short ranges, otherwise Yaroslavskiy's dual-pivot quicksort. It is not stable, so where the original OpenScan
 * code sorts with ties (e.g. equally sized components), using it instead of Kotlin's stable sort keeps the resulting order, and so the results, identical.
 */
internal fun <E> MutableList<E>.dartSort(compare: (E, E) -> Int) {
    DartSort.doSort(this, 0, size - 1, compare)
}

private object DartSort {
    private const val INSERTION_SORT_THRESHOLD = 32

    fun <E> doSort(a: MutableList<E>, left: Int, right: Int, compare: (E, E) -> Int) {
        if (right - left <= INSERTION_SORT_THRESHOLD) {
            insertionSort(a, left, right, compare)
        } else {
            dualPivotQuicksort(a, left, right, compare)
        }
    }

    private fun <E> insertionSort(a: MutableList<E>, left: Int, right: Int, compare: (E, E) -> Int) {
        for (i in left + 1..right) {
            val el = a[i]
            var j = i
            while (j > left && compare(a[j - 1], el) > 0) {
                a[j] = a[j - 1]
                j--
            }
            a[j] = el
        }
    }

    private fun <E> dualPivotQuicksort(a: MutableList<E>, left: Int, right: Int, compare: (E, E) -> Int) {
        val sixth = (right - left + 1) / 6
        val index1 = left + sixth
        val index5 = right - sixth
        val index3 = (left + right) / 2 // The midpoint.
        val index2 = index3 - sixth
        val index4 = index3 + sixth

        var el1 = a[index1]
        var el2 = a[index2]
        var el3 = a[index3]
        var el4 = a[index4]
        var el5 = a[index5]

        // Sort the selected 5 elements using a sorting network.
        if (compare(el1, el2) > 0) { val t = el1; el1 = el2; el2 = t }
        if (compare(el4, el5) > 0) { val t = el4; el4 = el5; el5 = t }
        if (compare(el1, el3) > 0) { val t = el1; el1 = el3; el3 = t }
        if (compare(el2, el3) > 0) { val t = el2; el2 = el3; el3 = t }
        if (compare(el1, el4) > 0) { val t = el1; el1 = el4; el4 = t }
        if (compare(el3, el4) > 0) { val t = el3; el3 = el4; el4 = t }
        if (compare(el2, el5) > 0) { val t = el2; el2 = el5; el5 = t }
        if (compare(el2, el3) > 0) { val t = el2; el2 = el3; el3 = t }
        if (compare(el4, el5) > 0) { val t = el4; el4 = el5; el5 = t }

        val pivot1 = el2
        val pivot2 = el4

        // el2 and el4 have been saved in the pivot variables. They will be written back once the partitioning is finished.
        a[index1] = el1
        a[index3] = el3
        a[index5] = el5

        a[index2] = a[left]
        a[index4] = a[right]

        var less = left + 1 // First element in the middle partition.
        var great = right - 1 // Last element in the middle partition.

        val pivotsAreEqual = compare(pivot1, pivot2) == 0
        if (pivotsAreEqual) {
            val pivot = pivot1
            var k = less
            while (k <= great) {
                val ak = a[k]
                var comp = compare(ak, pivot)
                if (comp == 0) {
                    k++
                    continue
                }
                if (comp < 0) {
                    if (k != less) {
                        a[k] = a[less]
                        a[less] = ak
                    }
                    less++
                } else {
                    while (true) {
                        comp = compare(a[great], pivot)
                        if (comp > 0) {
                            great--
                            continue
                        } else if (comp < 0) {
                            a[k] = a[less]
                            a[less++] = a[great]
                            a[great--] = ak
                            break
                        } else {
                            a[k] = a[great]
                            a[great--] = ak
                            break
                        }
                    }
                }
                k++
            }
        } else {
            var k = less
            while (k <= great) {
                val ak = a[k]
                val compPivot1 = compare(ak, pivot1)
                if (compPivot1 < 0) {
                    if (k != less) {
                        a[k] = a[less]
                        a[less] = ak
                    }
                    less++
                } else {
                    val compPivot2 = compare(ak, pivot2)
                    if (compPivot2 > 0) {
                        while (true) {
                            var comp = compare(a[great], pivot2)
                            if (comp > 0) {
                                great--
                                if (great < k) break
                                continue
                            } else {
                                comp = compare(a[great], pivot1)
                                if (comp < 0) {
                                    a[k] = a[less]
                                    a[less++] = a[great]
                                    a[great--] = ak
                                } else {
                                    a[k] = a[great]
                                    a[great--] = ak
                                }
                                break
                            }
                        }
                    }
                }
                k++
            }
        }

        // Move pivots into their final positions.
        a[left] = a[less - 1]
        a[less - 1] = pivot1
        a[right] = a[great + 1]
        a[great + 1] = pivot2

        // Recursively sort the left and right partitions.
        doSort(a, left, less - 2, compare)
        doSort(a, great + 2, right, compare)

        if (pivotsAreEqual) {
            // All elements in the middle partition are equal to the pivot.
            return
        }

        // If the middle partition is big, move the elements equal to the pivots out of it before sorting it.
        if (less < index1 && great > index5) {
            while (compare(a[less], pivot1) == 0) {
                less++
            }
            while (compare(a[great], pivot2) == 0) {
                great--
            }

            var k = less
            while (k <= great) {
                val ak = a[k]
                val compPivot1 = compare(ak, pivot1)
                if (compPivot1 == 0) {
                    if (k != less) {
                        a[k] = a[less]
                        a[less] = ak
                    }
                    less++
                } else {
                    val compPivot2 = compare(ak, pivot2)
                    if (compPivot2 == 0) {
                        while (true) {
                            var comp = compare(a[great], pivot2)
                            if (comp == 0) {
                                great--
                                if (great < k) break
                                continue
                            } else {
                                comp = compare(a[great], pivot1)
                                if (comp < 0) {
                                    a[k] = a[less]
                                    a[less++] = a[great]
                                    a[great--] = ak
                                } else {
                                    a[k] = a[great]
                                    a[great--] = ak
                                }
                                break
                            }
                        }
                    }
                }
                k++
            }
            doSort(a, less, great, compare)
        } else {
            doSort(a, less, great, compare)
        }
    }
}
