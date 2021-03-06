(function () {
   'use strict';
}());

angular.module('slicebox.anonymization', ['ngRoute'])

.config(function($routeProvider) {
  $routeProvider.when('/anonymization', {
    templateUrl: '/assets/partials/anonymization.html',
    controller: 'AnonymizationCtrl'
  });
})

.controller('AnonymizationCtrl', function($scope, $http, openMessageModal, openDeleteEntitiesModalFunction, openTagSeriesModalFunction, sbxToast, sbxUtil) {
    // Initialization
    $scope.actions =
        [
            {
                name: 'Delete',
                action: openDeleteEntitiesModalFunction('/api/anonymization/keys/', 'anonymization key(s)')
            }
        ];

    $scope.callbacks = {};

    if (!$scope.uiState.anonymizationTableState) {
        $scope.uiState.anonymizationTableState = {};
    }

    // Scope functions
    $scope.loadAnonymizationKeyPage = function(startIndex, count, orderByProperty, orderByDirection, filter) {
        var loadUrl = '/api/anonymization/keys?startindex=' + startIndex + '&count=' + count;
        if (orderByProperty) {
            loadUrl = loadUrl + '&orderby=' + orderByProperty;
            
            if (orderByDirection === 'ASCENDING') {
                loadUrl = loadUrl + '&orderascending=true';
            } else {
                loadUrl = loadUrl + '&orderascending=false';
            }
        }

        if (filter) {
            loadUrl = loadUrl + '&filter=' + encodeURIComponent(filter);
        }

        var loadPromise = $http.get(loadUrl);

        loadPromise.error(function(error) {
            sbxToast.showErrorMessage('Failed to load anonymization keys: ' + error);
        });

        return loadPromise;
    };

    $scope.keySelected = function(key) {
        $scope.uiState.selectedKey = key;

        if ($scope.callbacks.keyValuesTable) {
            $scope.callbacks.keyValuesTable.reset();
        }
    };

    $scope.loadKeyValues = function(startIndex, count, orderByProperty, orderByDirection, filter) {
        if ($scope.uiState.selectedKey === null) {
            return [];
        }

        return $http.get('/api/anonymization/keys/' + $scope.uiState.selectedKey.id + '/keyvalues').then(function(data) {
            if (filter) {
                var filterLc = filter.toLowerCase();
                data.data = data.data.filter(function (keyValue) {
                    var tagPathCondition = sbxUtil.tagPathToString(keyValue.tagPath).toLowerCase().indexOf(filterLc) >= 0;
                    var valueCondition = keyValue.value.toLowerCase().indexOf(filterLc) >= 0;
                    var anonCondition = keyValue.anonymizedValue.toLowerCase().indexOf(filterLc) >= 0;
                    return tagPathCondition || valueCondition || anonCondition;
                });
            }
            if (orderByProperty) {
                if (!orderByDirection) {
                    orderByDirection = 'ASCENDING';
                }
                return data.data.sort(function compare(a,b) {
                    return orderByDirection === 'ASCENDING' ?
                        a[orderByProperty] < b[orderByProperty] ? -1 : a[orderByProperty] > b[orderByProperty] ? 1 : 0 :
                        a[orderByProperty] > b[orderByProperty] ? -1 : a[orderByProperty] < b[orderByProperty] ? 1 : 0;
                });
            } else {
                return data.data;
            }
        }, function(error) {
            sbxToast.showErrorMessage('Failed to load key values for anonymization key: ' + error);
        });
    };
});
