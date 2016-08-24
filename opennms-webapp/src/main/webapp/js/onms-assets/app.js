/**
* @author Alejandro Galue <agalue@opennms.org>
* @copyright 2016 The OpenNMS Group, Inc.
*/

'use strict';

angular.module('onms-assets', [
  'ui.bootstrap',
  'angular-growl'
])

.config(['growlProvider', function(growlProvider) {
  growlProvider.globalTimeToLive(3000);
  growlProvider.globalPosition('bottom-center');
}])

.config(['$uibTooltipProvider', function($uibTooltipProvider) {
  $uibTooltipProvider.setTriggers({
    'mouseenter': 'mouseleave'
  });
  $uibTooltipProvider.options({
    'placement': 'right',
    'trigger': 'mouseenter'
  });
}])

.directive('assetField', function() {
  return {
    restrict: 'E',
    templateUrl: 'js/onms-assets/template.html',
    scope: {
      field: '=',
      asset: '=',
      form: '='
    },
    link: function (scope, element, attrs) {
      scope.suggestions = scope.$parent.suggestions;
      scope.getSuggestions = function(field) {
        if (scope.suggestions[field]) {
          return scope.suggestions[field].suggestion;
        }
        return [];
      };
    }
  };
})

.controller('NodeAssetsCtrl', ['$scope', '$http', '$q', 'growl', function($scope, $http, $q, growl) {

  $scope.infoKeys = [ 'sysObjectId', 'sysName', 'sysLocation', 'sysContact', 'sysDescription' ];
  $scope.config = {};
  $scope.master = {};
  $scope.asset = {};
  $scope.suggestions = {};
  $scope.nodeId;
  $scope.nodeLabel;
  $scope.foreignSource;
  $scope.foreignId;

  $scope.init = function(nodeId) {
    $scope.nodeId = nodeId;
    $http.get('js/onms-assets/config.json')
      .success(function(config) {
        $scope.config = config;
        $http.get('rest/nodes/' + $scope.nodeId)
          .success(function(node) {
            $scope.nodeLabel = node.label;
            $scope.foreignSource = node.foreignSource;
            $scope.foreignId = node.foreignId;
            $scope.master = angular.copy(node.assetRecord);
            $scope.asset = angular.copy(node.assetRecord);
            angular.forEach($scope.infoKeys, function(k) {
              $scope.asset[k] = node[k];
            });
          })
          .error(function(msg) {
            growl.error(msg);
          });
      })
      .error(function(msg) {
        growl.error(msg);
      });
    $http.get('rest/assets/suggestions')
      .success(function(suggestions) {
        $scope.suggestions = suggestions
      })
      .error(function(msg) {
        growl.error(msg);
      });
  };

  $scope.reset = function() {
    $scope.asset = angular.copy($scope.master);
  };

  $scope.save = function() {
    var target = {};
    for (var k in $scope.asset) {
      if ($scope.infoKeys.indexOf(k) == -1 && k != 'id' && $scope.asset[k] != '' && $scope.asset[k] != null) {
        target[k] = $scope.asset[k];
      }
    }
    console.log(target);
    $http({
      method: 'PUT',
      url: 'rest/nodes/' + $scope.nodeId + '/assetRecord',
      headers: {'Content-Type': 'application/x-www-form-urlencoded'},
      data: $.param(target)
    }).success(function() {
      growl.success('The asset record has been successfully updated.');
      $scope.checkRequisition(target);
    }).error(function(msg) {
      growl.error('Cannot update the asset record: ' + msg);
    });
  };

  $scope.checkRequisition = function(assets) {
    if ($scope.foreignSource && $scope.foreignId) {
      bootbox.confirm('This node belongs to the requisition ' + $scope.foreignSource + '.<br/> It is recommended to update the requisition with your asset fields, but all the existing fields will be overriden.<br/> Do you want to do that ?', function(ok) {
        if (ok) {
          $scope.updateRequisition(assets);
        }
      });
    }
  };

  $scope.updateRequisition = function(assets) {
    var assetFields = [];
    for (var key in assets) {
      assetFields.push({ name: key, value: assets[key] });
    }
    $http.get('rest/requisitions/' + $scope.foreignSource + '/nodes/' + $scope.foreignId)
      .success(function(node) {
        node.asset = assetFields;
        $http.post('rest/requisitions/' + $scope.foreignSource + '/nodes', node)
          .success(function() {
            growl.success('Requisition ' + $scope.foreignSource + ' has been updated.');
          })
          .error(function() {
            growl.error('Cannot update requisition ' + $scope.foreignSource);
          });
      })
      .error(function() {
        growl.error('Cannot obtain node data from requisition ' + $scope.foreignSource);
      });
  };

}]);
