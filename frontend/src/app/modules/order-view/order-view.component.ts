import { Component, OnInit, ViewChild, AfterViewInit, Input, ContentChild } from '@angular/core';
import { Order } from 'src/app/core/models/order';
import { SelectionModel } from '@angular/cdk/collections';
import { MatPaginator } from '@angular/material/paginator';
import { MatDialog } from '@angular/material/dialog';
import { RestService } from 'src/app/core/services/rest.service';
import { MatTableDataSource, MatTable } from '@angular/material/table';
import {MatSort, MatSortable} from '@angular/material/sort';
import { animate, state, style, transition, trigger } from '@angular/animations';
import { SocketService } from 'src/app/core/services/socket.service';
//import {CdkDetailRowDirective } from 'src/app/shared/components/cdk-detail-row.directive';


@Component({
  selector: 'app-order-view',
  templateUrl: './order-view.component.html',
  styleUrls: ['./order-view.component.scss'],
  animations: [
    trigger('detailExpand', [
      state('collapsed', style({height: '0px', minHeight: '0', visibility: 'hidden'})),
      state('expanded', style({height: '*', visibility: 'visible'})),
      transition('expanded <=> collapsed', animate('225ms cubic-bezier(0.4, 0.0, 0.2, 1)')),
    ]),
],
})
export class OrderViewComponent implements OnInit {
  selection = new SelectionModel<Order>(false, []);
  displayedColumns: string[] = ['select',  'id', 'customerId','status','product', 'productQty', 'voyageId'];//, 'origin', 'destination','sailDate', 'transitTime', 'voyageId', 'reeferIds'];
  orders: Order[] = [];
  orderTarget : number = 85;
  filterValues = {};
  filterSelectObj = [];

  autoSimButtonLabel: string = "Update";
  dataSource = new MatTableDataSource(this.orders);
 // isExpansionDetailRow = (i: number, row: Object) => row.hasOwnProperty('detailRow');
  public currentExpandedRow: any;
  //public expandRow: boolean = false;
  public expandedElement: boolean = true;
  @ViewChild(MatPaginator, { static: true }) paginator: MatPaginator;
  //@ViewChild(MatSort) sort: MatSort;
  @ViewChild(MatSort, {static: true}) sort: MatSort;
  
  //@ViewChild(MatTable) table: MatTable<any>;

  //@Input() sorting: MatSortable;

  constructor(private dialog: MatDialog, private restService: RestService, private webSocketService : SocketService) { 
    let stompClient = this.webSocketService.connect();
    console.log('OrderView - connected socket');
    stompClient.connect({}, frame => {
      console.log('OrderView - connected stompClient');
  // Subscribe to notification topic
        stompClient.subscribe('/topic/orders', (event:any) => {
          if ( event.body) {
            let order: Order;
            order = JSON.parse(event.body);

            //this.dataSource.data.forEach
            const currentData = this.dataSource.data;

            currentData.unshift(order);
            console.log('::::::'+order);
            this.dataSource.data = currentData;
            this.dataSource.sort = this.sort;
          }

        })
    });

  }

  isExpansionDetailRow = (_, row: any) => row.hasOwnProperty('detailRow');
  explansionDetialRowCollection = new Array<any>();
/*
  public toggleDetailsRow(row: any): void {
    this.expandRow = this.explansionDetialRowCollection.includes(row);
    if(this.expandRow !== true) {
      this.explansionDetialRowCollection.push(row);
    } else {
      // let index = this.explansionDetialRowCollection.findIndex(idRow => idRow.name === row.element.name);
      let test = this.explansionDetialRowCollection[0].name;
      this.explansionDetialRowCollection.forEach( (item, index) => {
        if(item.position === row.position) this.explansionDetialRowCollection.splice(index, 1);
      });
      // this.explansionDetialRowCollection.splice(0, 1);
    }
  }
  */
  ngOnInit(): void {
     this.restService.getOrderTarget().subscribe((data) => {
      console.log(data);
      if ( data < 0 ) {
        data = 0;
      }
      this.orderTarget = data;
    });
    this.restService.getAllOrders().subscribe((data) => {
      console.log(data);
      this.dataSource.data = data;
    //this.dataSource =  new MatTableDataSource(data);
    this.dataSource.paginator = this.paginator;
    this.dataSource.sort = this.sort;

     }


    );
 //    this.dataSource.paginator = this.paginator;
 //    this.dataSource.sort = this.sort;

  }

  toggleEnableDisable(event: Event) {
    console.log("Click "+event);

    this.restService.setOrderTarget(this.orderTarget).subscribe((data) => {
      console.log(data);
    });
    
  }
  nextOrder() {
    console.log('>>>>>>>>>>>>>nextOrder called');
    
    this.restService.createOrder().subscribe((data) => {
      console.log(data);
      //this.date = data.substr(0,10);
    });
    
   // this.getActiveVoyages();
  }
  orderTargetChange(event: any ) {
    this.orderTarget = event.target.value;
    if (this.orderTarget == 0 ) {
      this.restService.setOrderTarget(0).subscribe((data) => {
        console.log(data);
      });
    }
  }
  selectedOrder($event, row?: Order) {
  //  const numSelected = this.selection.selected.length;
  //  if ($event.checked) {
  //    console.log(row);
  //    this.saveOrder(this.selectedProduct, 1000, this.selectedOriginPort, this.selectedDestinationPort,row);
  //  }

  }
 /** Whether the number of selected elements matches the total number of rows. */
 isAllSelected() {
  const numSelected = this.selection.selected.length;
  const numRows = this.dataSource.data.length;
  return numSelected === numRows;
}

/** Selects all rows if they are not all selected; otherwise clear selection. */
masterToggle() {

  this.isAllSelected() ?
      this.selection.clear() :
      this.dataSource.data.forEach(row => this.selection.select(row));
}

/** The label for the checkbox on the passed row */
checkboxLabel(row?: Order): string {

  if (!row) {
    return `${this.isAllSelected() ? 'select' : 'deselect'} all`;
  }

  return `${this.selection.isSelected(row) ? 'deselect' : 'select'} all`;
}
public doFilter = (value: string) => {
  this.dataSource.filter = value.trim().toLocaleLowerCase();
}

// Get Uniqu values from columns to build filter
getFilterObject(fullObj, key) {
  const uniqChk = [];
  fullObj.filter((obj) => {
    if (!uniqChk.includes(obj[key])) {
      uniqChk.push(obj[key]);
    }
    return obj;
  });
  return uniqChk;
}
// Called on Filter change
filterChange(filter, event) {
  //let filterValues = {}
  this.filterValues[filter.columnProp] = event.target.value.trim().toLowerCase()
  this.dataSource.filter = JSON.stringify(this.filterValues)
}
// Custom filter method fot Angular Material Datatable
createFilter() {
let filterFunction = function (data: any, filter: string): boolean {
  let searchTerms = JSON.parse(filter);
  let isFilterSet = false;
  for (const col in searchTerms) {
    if (searchTerms[col].toString() !== '') {
      isFilterSet = true;
    } else {
      delete searchTerms[col];
    }
  }

  console.log(searchTerms);

  let nameSearch = () => {
    let found = false;
    if (isFilterSet) {
      for (const col in searchTerms) {
        searchTerms[col].trim().toLowerCase().split(' ').forEach(word => {
          if (data[col].toString().toLowerCase().indexOf(word) != -1 && isFilterSet) {
            found = true
          }
        });
      }
      return found
    } else {
      return true;
    }
  }
  return nameSearch()
}
return filterFunction
}


// Reset table filters
resetFilters() {
this.filterValues = {}
this.filterSelectObj.forEach((value, key) => {
  value.modelValue = undefined;
})
this.dataSource.filter = "";
}

}
